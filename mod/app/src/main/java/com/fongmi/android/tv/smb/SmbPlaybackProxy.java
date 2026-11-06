package com.fongmi.android.tv.smb;

import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SmbPlaybackProxy {

    private static final Map<String, FileRef> FILES = new LinkedHashMap<>();
    private static ProxyServer server;

    private SmbPlaybackProxy() {
    }

    public static synchronized String createUrl(String host, String share, String path,
                                                String username, String password, String domain) throws IOException {
        ensureServer();
        String token = UUID.randomUUID().toString().replace("-", "");
        FILES.put(token, new FileRef(host, share, path, username, password, domain));
        while (FILES.size() > 24) {
            String first = FILES.keySet().iterator().next();
            FILES.remove(first);
        }
        return "http://127.0.0.1:" + server.getListeningPort() + "/" + token;
    }

    private static void ensureServer() throws IOException {
        if (server != null && server.getListeningPort() > 0) return;
        server = new ProxyServer();
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    private static final class ProxyServer extends NanoHTTPD {
        ProxyServer() {
            super("127.0.0.1", 0);
        }

        @Override
        public Response serve(IHTTPSession request) {
            String uri = request.getUri();
            String token = uri == null ? "" : uri.replaceFirst("^/", "");
            FileRef ref;
            synchronized (SmbPlaybackProxy.class) {
                ref = FILES.get(token);
            }
            if (ref == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");

            SmbClient.RemoteFile remote = null;
            try {
                remote = SmbClient.openFile(ref.host, ref.share, ref.path,
                        ref.username, ref.password, ref.domain);
                long size = remote.length();
                String mime = mime(ref.path);
                String range = request.getHeaders().get("range");
                long start = 0;
                long end = Math.max(0, size - 1);
                boolean partial = false;
                if (range != null && range.startsWith("bytes=") && size > 0) {
                    String value = range.substring(6).trim();
                    int dash = value.indexOf('-');
                    if (dash > 0) {
                        try {
                            start = Long.parseLong(value.substring(0, dash).trim());
                            String tail = value.substring(dash + 1).trim();
                            if (!tail.isEmpty()) end = Math.min(size - 1, Long.parseLong(tail));
                            if (start >= 0 && start < size && end >= start) partial = true;
                        } catch (Exception ignored) {
                            start = 0;
                            end = size - 1;
                        }
                    }
                }
                if (size <= 0) {
                    remote.close();
                    return newFixedLengthResponse(Response.Status.NO_CONTENT, mime, "");
                }
                if (request.getMethod() == Method.HEAD) {
                    remote.close();
                    Response head = newFixedLengthResponse(Response.Status.OK, mime, "");
                    head.addHeader("Content-Length", String.valueOf(size));
                    head.addHeader("Accept-Ranges", "bytes");
                    return head;
                }
                final SmbClient.RemoteFile streamFile = remote;
                final long streamStart = start;
                final long streamEnd = end;
                final long contentLength = end - start + 1;
                final InputStream input = new InputStream() {
                    private long position = streamStart;

                    @Override
                    public int read() throws IOException {
                        byte[] one = new byte[1];
                        int count = read(one, 0, 1);
                        return count < 0 ? -1 : one[0] & 255;
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        if (length == 0) return 0;
                        if (position > streamEnd) return -1;
                        int wanted = (int) Math.min((long) length, streamEnd - position + 1);
                        try {
                            int count = streamFile.read(buffer, position, offset, wanted);
                            if (count <= 0) return -1;
                            position += count;
                            return count;
                        } catch (RuntimeException e) {
                            throw new IOException(e);
                        }
                    }

                    @Override
                    public void close() {
                        streamFile.close();
                    }
                };
                Response response = newFixedLengthResponse(
                        partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
                        mime, input, contentLength);
                response.addHeader("Accept-Ranges", "bytes");
                response.addHeader("Cache-Control", "no-store");
                if (partial) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
                return response;
            } catch (Exception e) {
                if (remote != null) remote.close();
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain",
                        "SMB read failed: " + e.getClass().getSimpleName());
            }
        }
    }

    private static String mime(String path) {
        String value = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (value.endsWith(".mp4") || value.endsWith(".m4v")) return "video/mp4";
        if (value.endsWith(".mkv")) return "video/x-matroska";
        if (value.endsWith(".ts") || value.endsWith(".m2ts")) return "video/mp2t";
        if (value.endsWith(".avi")) return "video/x-msvideo";
        if (value.endsWith(".webm")) return "video/webm";
        if (value.endsWith(".mov")) return "video/quicktime";
        if (value.endsWith(".mpg") || value.endsWith(".mpeg")) return "video/mpeg";
        return "application/octet-stream";
    }

    private static final class FileRef {
        final String host;
        final String share;
        final String path;
        final String username;
        final String password;
        final String domain;

        FileRef(String host, String share, String path, String username, String password, String domain) {
            this.host = host;
            this.share = share;
            this.path = path;
            this.username = username;
            this.password = password;
            this.domain = domain;
        }
    }
}
