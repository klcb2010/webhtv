package com.fongmi.android.tv.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.Share;
import com.rapid7.client.dcerpc.mssrvs.ServerService;
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo0;
import com.rapid7.client.dcerpc.transport.RPCTransport;
import com.rapid7.client.dcerpc.transport.SMBTransportFactories;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public final class SmbClient {

    private SmbClient() {
    }

    private static AuthenticationContext auth(String username, String password, String domain) {
        // 空用户名使用真正的 SMB 匿名会话；部分 NAS 会把 Guest 与 Anonymous 区分处理。
        if (username == null || username.trim().isEmpty()) return AuthenticationContext.anonymous();
        return new AuthenticationContext(username, (password == null ? "" : password).toCharArray(),
                domain == null || domain.isEmpty() ? null : domain);
    }

    private static Session authenticate(Connection connection, String username, String password, String domain) throws Exception {
        if (username == null || username.trim().isEmpty()) {
            try {
                return connection.authenticate(AuthenticationContext.anonymous());
            } catch (Exception anonymousError) {
                return connection.authenticate(AuthenticationContext.guest());
            }
        }
        return connection.authenticate(auth(username, password, domain));
    }

    public static List<String> listShares(String host, String username, String password, String domain) throws Exception {
        List<String> result = new ArrayList<>();
        try (SMBClient client = new SMBClient();
             Connection connection = client.connect(host);
             Session session = authenticate(connection, username, password, domain)) {
            RPCTransport transport = SMBTransportFactories.SRVSVC.getTransport(session);
            ServerService service = new ServerService(transport);
            for (NetShareInfo0 item : service.getShares0()) {
                String name = item.getNetName();
                if (name == null || name.isEmpty()) continue;
                String upper = name.toUpperCase(java.util.Locale.ROOT);
                if ("IPC$".equals(upper) || "ADMIN$".equals(upper) || "PRINT$".equals(upper)
                        || (upper.length() == 2 && upper.charAt(1) == 36)) continue;
                result.add(name);
            }
        }
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public static List<Entry> listFiles(String host, String shareName, String path,
                                        String username, String password, String domain) throws Exception {
        List<Entry> result = new ArrayList<>();
        try (SMBClient client = new SMBClient();
             Connection connection = client.connect(host);
             Session session = authenticate(connection, username, password, domain)) {
            Share remote = session.connectShare(shareName);
            if (!(remote instanceof DiskShare)) {
                remote.close();
                throw new IllegalStateException("Not a disk share");
            }
            try (DiskShare share = (DiskShare) remote) {
                String directory = path == null ? "" : path;
                for (FileIdBothDirectoryInformation item : share.list(directory)) {
                    String name = item.getFileName();
                    if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) continue;
                    boolean isDirectory = (item.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                    if (!isDirectory && !isVideo(name) && !isAudio(name) && !isImage(name) && !isApk(name)) continue;
                    result.add(new Entry(name, isDirectory));
                }
            }
        }
        Collections.sort(result, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.directory != b.directory) return a.directory ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return result;
    }


    public static void deleteRemote(String host, String shareName, String remotePath,
                                    boolean directory, String username, String password,
                                    String domain) throws Exception {
        if (remotePath == null || remotePath.trim().isEmpty()) {
            throw new IllegalArgumentException("Refusing to delete an empty SMB path");
        }
        try (SMBClient client = new SMBClient();
             Connection connection = client.connect(host);
             Session session = authenticate(connection, username, password, domain)) {
            Share remote = session.connectShare(shareName);
            if (!(remote instanceof DiskShare)) {
                remote.close();
                throw new IllegalStateException("Not a disk share");
            }
            try (DiskShare diskShare = (DiskShare) remote) {
                if (directory) diskShare.rmdir(remotePath, true);
                else diskShare.rm(remotePath);
            }
        }
    }

    public interface CopyProgressListener {
        boolean isCancelled();
        void onProgress(long copiedBytes, long totalBytes, String currentName);
    }

    private static final class CopyEntry {
        final String remotePath;
        final String relativePath;
        final boolean directory;
        final long size;

        CopyEntry(String remotePath, String relativePath, boolean directory, long size) {
            this.remotePath = remotePath;
            this.relativePath = relativePath;
            this.directory = directory;
            this.size = size;
        }
    }

    public static void copyToLocal(String host, String shareName, String remotePath,
                                   boolean directory, File destination,
                                   String username, String password, String domain,
                                   CopyProgressListener listener) throws Exception {
        if (destination == null || destination.exists()) {
            throw new IOException("Download 中已存在同名项目");
        }
        File parent = destination.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs()) || !parent.isDirectory()) {
            throw new IOException("无法创建 Download 目录");
        }

        List<CopyEntry> entries = new ArrayList<>();
        long totalBytes;
        try (SMBClient client = new SMBClient();
             Connection connection = client.connect(host);
             Session session = authenticate(connection, username, password, domain)) {
            Share remote = session.connectShare(shareName);
            if (!(remote instanceof DiskShare)) {
                remote.close();
                throw new IOException("SMB 共享不支持文件复制");
            }
            try (DiskShare diskShare = (DiskShare) remote) {
                if (directory) {
                    collectCopyEntries(diskShare, remotePath, "", entries);
                } else {
                    try (com.hierynomus.smbj.share.File source = diskShare.openFile(
                            remotePath, EnumSet.of(AccessMask.GENERIC_READ), null,
                            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ,
                                    SMB2ShareAccess.FILE_SHARE_WRITE,
                                    SMB2ShareAccess.FILE_SHARE_DELETE),
                            SMB2CreateDisposition.FILE_OPEN, null)) {
                        long size = source.getFileInformation(FileStandardInformation.class).getEndOfFile();
                        entries.add(new CopyEntry(remotePath, "", false, size));
                    }
                }

                totalBytes = 0;
                for (CopyEntry entry : entries) {
                    if (!entry.directory) totalBytes += Math.max(0, entry.size);
                }
                if (parent.getUsableSpace() < totalBytes) {
                    throw new IOException("Download 剩余空间不足");
                }

                if (directory) {
                    if (!destination.mkdirs()) throw new IOException("无法创建目标文件夹");
                }

                long copiedBytes = 0;
                byte[] buffer = new byte[64 * 1024];
                try {
                    for (CopyEntry entry : entries) {
                        if (listener != null && listener.isCancelled()) {
                            throw new IOException("用户已取消复制");
                        }
                        File outputFile = entry.relativePath.isEmpty()
                                ? destination : new File(destination, entry.relativePath);
                        if (entry.directory) {
                            if (!outputFile.exists() && !outputFile.mkdirs()) {
                                throw new IOException("无法创建文件夹：" + entry.relativePath);
                            }
                            if (listener != null) listener.onProgress(copiedBytes, totalBytes, entry.relativePath);
                            continue;
                        }

                        File outputParent = outputFile.getParentFile();
                        if (outputParent != null && !outputParent.exists() && !outputParent.mkdirs()) {
                            throw new IOException("无法创建目录：" + entry.relativePath);
                        }
                        if (outputFile.exists()) throw new IOException("目标中存在同名文件：" + entry.relativePath);

                        try (com.hierynomus.smbj.share.File source = diskShare.openFile(
                                entry.remotePath, EnumSet.of(AccessMask.GENERIC_READ), null,
                                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ,
                                        SMB2ShareAccess.FILE_SHARE_WRITE,
                                        SMB2ShareAccess.FILE_SHARE_DELETE),
                                SMB2CreateDisposition.FILE_OPEN, null);
                             BufferedOutputStream output = new BufferedOutputStream(
                                     new FileOutputStream(outputFile))) {
                            long offset = 0;
                            long fileSize = source.getFileInformation(FileStandardInformation.class).getEndOfFile();
                            while (offset < fileSize) {
                                if (listener != null && listener.isCancelled()) {
                                    throw new IOException("用户已取消复制");
                                }
                                int count = source.read(buffer, offset, 0,
                                        (int) Math.min((long) buffer.length, fileSize - offset));
                                if (count <= 0) throw new IOException("文件读取不完整：" + entry.relativePath);
                                output.write(buffer, 0, count);
                                offset += count;
                                copiedBytes += count;
                                if (listener != null) listener.onProgress(copiedBytes, totalBytes, entry.relativePath);
                            }
                            output.flush();
                        }
                    }
                } catch (Exception error) {
                    deleteLocalRecursively(destination);
                    throw error;
                }
            }
        }
    }

    private static void collectCopyEntries(DiskShare share, String remoteDirectory,
                                           String relativeDirectory, List<CopyEntry> entries) throws Exception {
        entries.add(new CopyEntry(remoteDirectory, relativeDirectory, true, 0));
        for (FileIdBothDirectoryInformation item : share.list(remoteDirectory)) {
            String name = item.getFileName();
            if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) continue;
            String childRemote = remoteDirectory == null || remoteDirectory.isEmpty()
                    ? name : remoteDirectory + "\\" + name;
            String childRelative = relativeDirectory.isEmpty()
                    ? name : relativeDirectory + File.separator + name;
            boolean childDirectory = (item.getFileAttributes()
                    & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
            if (childDirectory) {
                collectCopyEntries(share, childRemote, childRelative, entries);
            } else {
                entries.add(new CopyEntry(childRemote, childRelative, false,
                        Math.max(0, item.getEndOfFile())));
            }
        }
    }

    private static void deleteLocalRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteLocalRecursively(child);
        }
        // 仅清理由本次复制创建的目标，失败时不触碰 Download 中其他内容。
        file.delete();
    }

    public static boolean isVideo(String name) {
        if (name == null) return false;
        String value = name.toLowerCase(java.util.Locale.ROOT);
        return value.endsWith(".mp4") || value.endsWith(".mkv") || value.endsWith(".avi")
                || value.endsWith(".mov") || value.endsWith(".m4v") || value.endsWith(".ts")
                || value.endsWith(".m2ts") || value.endsWith(".wmv") || value.endsWith(".flv")
                || value.endsWith(".webm") || value.endsWith(".mpg") || value.endsWith(".mpeg")
                || value.endsWith(".vob") || value.endsWith(".3gp") || value.endsWith(".3g2")
                || value.endsWith(".mts") || value.endsWith(".mxf") || value.endsWith(".ogv");
    }

    public static boolean isAudio(String name) {
        if (name == null) return false;
        String value = name.toLowerCase(java.util.Locale.ROOT);
        return value.endsWith(".mp3") || value.endsWith(".flac") || value.endsWith(".m4a")
                || value.endsWith(".m4b") || value.endsWith(".aac") || value.endsWith(".wav")
                || value.endsWith(".ogg") || value.endsWith(".opus") || value.endsWith(".wma")
                || value.endsWith(".ape") || value.endsWith(".aiff") || value.endsWith(".aif")
                || value.endsWith(".amr") || value.endsWith(".mid") || value.endsWith(".midi")
                || value.endsWith(".mka") || value.endsWith(".ac3") || value.endsWith(".dts");
    }

    public static boolean isImage(String name) {
        if (name == null) return false;
        String value = name.toLowerCase(java.util.Locale.ROOT);
        return value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".jpe")
                || value.endsWith(".png") || value.endsWith(".gif") || value.endsWith(".bmp")
                || value.endsWith(".webp") || value.endsWith(".heic") || value.endsWith(".heif")
                || value.endsWith(".avif") || value.endsWith(".tif") || value.endsWith(".tiff");
    }

    public static boolean isApk(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk");
    }

    public static RemoteFile openFile(String host, String shareName, String path,
                                      String username, String password, String domain) throws Exception {
        SMBClient client = new SMBClient();
        Connection connection = null;
        Session session = null;
        DiskShare share = null;
        com.hierynomus.smbj.share.File file = null;
        try {
            connection = client.connect(host);
            session = authenticate(connection, username, password, domain);
            Share remote = session.connectShare(shareName);
            if (!(remote instanceof DiskShare)) throw new IllegalStateException("Not a disk share");
            share = (DiskShare) remote;
            file = share.openFile(path,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                    SMB2CreateDisposition.FILE_OPEN,
                    null);
            return new RemoteFile(client, connection, session, share, file);
        } catch (Exception e) {
            closeQuietly(file);
            closeQuietly(share);
            closeQuietly(session);
            closeQuietly(connection);
            closeQuietly(client);
            throw e;
        }
    }

    private static void closeQuietly(AutoCloseable value) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception ignored) {
        }
    }

    public static final class Entry {
        public final String name;
        public final boolean directory;

        Entry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }
    }

    public static final class RemoteFile implements AutoCloseable {
        private final SMBClient client;
        private final Connection connection;
        private final Session session;
        private final DiskShare share;
        private final com.hierynomus.smbj.share.File file;

        RemoteFile(SMBClient client, Connection connection, Session session,
                   DiskShare share, com.hierynomus.smbj.share.File file) {
            this.client = client;
            this.connection = connection;
            this.session = session;
            this.share = share;
            this.file = file;
        }

        public long length() throws Exception {
            return file.getFileInformation(FileStandardInformation.class).getEndOfFile();
        }

        public int read(byte[] buffer, long fileOffset, int offset, int length) {
            return file.read(buffer, fileOffset, offset, length);
        }

        @Override
        public void close() {
            closeQuietly(file);
            closeQuietly(share);
            closeQuietly(session);
            closeQuietly(connection);
            closeQuietly(client);
        }
    }
}
