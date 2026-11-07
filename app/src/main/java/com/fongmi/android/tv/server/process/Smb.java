package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.smb.SmbClient;
import com.fongmi.android.tv.smb.SmbPlaybackProxy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class Smb implements Process {

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/smb/");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        Map<String, String> params = session.getParms();
        String host = value(params, "host");
        String share = value(params, "share");
        String path = value(params, "path").replace('/', '\\');
        String username = value(params, "username");
        String password = value(params, "password");
        String domain = value(params, "domain");
        if (host.isEmpty()) return Nano.error(Status.BAD_REQUEST, "Missing SMB host");
        try {
            if (url.startsWith("/smb/list")) {
                JsonObject result = new JsonObject();
                JsonArray items = new JsonArray();
                if (share.isEmpty()) {
                    result.addProperty("mode", "shares");
                    List<String> shares = SmbClient.listShares(host, username, password, domain);
                    for (String name : shares) {
                        JsonObject item = new JsonObject();
                        item.addProperty("name", name);
                        item.addProperty("dir", 1);
                        items.add(item);
                    }
                } else {
                    result.addProperty("mode", "files");
                    List<SmbClient.Entry> entries = SmbClient.listFiles(host, share, path, username, password, domain);
                    for (SmbClient.Entry entry : entries) {
                        JsonObject item = new JsonObject();
                        item.addProperty("name", entry.name);
                        item.addProperty("dir", entry.directory ? 1 : 0);
                        items.add(item);
                    }
                }
                result.add("items", items);
                return Nano.ok(result.toString());
            }
            if (url.startsWith("/smb/play")) {
                if (share.isEmpty() || path.isEmpty() || !SmbClient.isVideo(path)) {
                    return Nano.error(Status.BAD_REQUEST, "Invalid SMB video path");
                }
                JsonObject result = new JsonObject();
                result.addProperty("url", SmbPlaybackProxy.createUrl(host, share, path, username, password, domain));
                return Nano.ok(result.toString());
            }
            return Nano.error(Status.NOT_FOUND, "Not found");
        } catch (Exception e) {
            String message = e.getMessage();
            return Nano.error(Status.BAD_REQUEST, message == null ? e.getClass().getSimpleName() : message);
        }
    }

    private String value(Map<String, String> params, String key) {
        String value = params.get(key);
        return value == null ? "" : value.trim();
    }
}
