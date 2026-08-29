package com.delrisco.trap21;

import java.net.InetAddress;
import java.nio.file.Path;

public final class Trap21Application {
    private Trap21Application() {
    }

    public static void main(String[] args) throws Exception {
        InetAddress bind = InetAddress.getByName(env("TRAP21_BIND", "127.0.0.1"));
        String publicHost = env("TRAP21_PUBLIC_HOST", "127.0.0.1");
        Path dataDir = Path.of(env("TRAP21_DATA_DIR", "data")).toAbsolutePath().normalize();

        Trap21Server server = new Trap21Server(bind, publicHost, dataDir);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            try {
                server.close();
            } catch (Exception ignored) {
                // JVM shutdown is best-effort.
            }
        }));
        server.start();
        System.out.printf("TRAP21 listening on %s:%d%n", bind.getHostAddress(), server.port());
        System.out.printf("Evidence: %s%n", dataDir);
        server.awaitTermination();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
