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
                    if (!isDirectory && !isVideo(name)) continue;
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
