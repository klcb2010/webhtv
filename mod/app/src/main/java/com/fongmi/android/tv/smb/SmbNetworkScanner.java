package com.fongmi.android.tv.smb;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class SmbNetworkScanner {

    private SmbNetworkScanner() {
    }

    public static List<String> scan() throws Exception {
        Set<String> targets = new LinkedHashSet<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            try {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
            } catch (Exception ignored) {
                continue;
            }
            for (java.net.InterfaceAddress item : network.getInterfaceAddresses()) {
                InetAddress address = item.getAddress();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                byte[] bytes = address.getAddress();
                int ip = ((bytes[0] & 255) << 24) | ((bytes[1] & 255) << 16)
                        | ((bytes[2] & 255) << 8) | (bytes[3] & 255);
                if (!isPrivate(ip)) continue;
                int prefix = item.getNetworkPrefixLength();
                if (prefix < 1 || prefix > 30) continue;
                // Avoid sweeping huge routed networks; SMB discovery is scoped to the local /24.
                int mask = prefix < 24 ? 0xffffff00 : (int) (0xffffffffL << (32 - prefix));
                int networkAddress = ip & mask;
                int broadcast = networkAddress | ~mask;
                for (int candidate = networkAddress + 1; candidate < broadcast; candidate++) {
                    if (candidate == ip) continue;
                    targets.add(toIp(candidate));
                }
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(96);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (final String host : targets) {
                tasks.add(new Callable<String>() {
                    @Override
                    public String call() {
                        try (Socket socket = new Socket()) {
                            socket.setTcpNoDelay(true);\n                            socket.connect(new InetSocketAddress(host, 445), 180);
                            return host;
                        } catch (Exception ignored) {
                            return null;
                        }
                    }
                });
            }
            List<Future<String>> futures = pool.invokeAll(tasks, 3, TimeUnit.SECONDS);
            List<String> found = new ArrayList<>();
            for (Future<String> future : futures) {
                if (future.isCancelled()) continue;
                try {
                    String host = future.get();
                    if (host != null) found.add(host);
                } catch (Exception ignored) {
                }
            }
            Collections.sort(found, (a, b) -> Long.compare(ipValue(a), ipValue(b)));
            return found;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long ipValue(String value) {
        String[] parts = value.split("\\.");
        long result = 0;
        for (String part : parts) result = (result << 8) | Integer.parseInt(part);
        return result;
    }

    private static boolean isPrivate(int ip) {
        int a = (ip >>> 24) & 255;
        int b = (ip >>> 16) & 255;
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }


    private static String toIp(int value) {
        return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "."
                + ((value >>> 8) & 255) + "." + (value & 255);
    }
}
