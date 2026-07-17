package com.delrisco.trap21;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public final class Trap21Application {
    private Trap21Application() {
    }

    public static void main(String[] args) throws Exception {
        Trap21Config config = Trap21Config.fromEnvironment();
        Trap21Server server = new Trap21Server(config);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> closeQuietly(server)));
        server.start();
        printStartup(config, server.port());
        new CountDownLatch(1).await();
    }

    private static void printStartup(Trap21Config config, int boundPort) {
        System.out.println();
        System.out.println("TRAP21");
        System.out.println("FTP DECEPTION HONEYPOT");
        System.out.println("Del Risco Technologies");
        System.out.println();
        System.out.printf("Control listener  : %s:%d%n", config.bindAddress().getHostAddress(), boundPort);
        System.out.printf("Passive range     : %d-%d%n", config.passivePortStart(), config.passivePortEnd());
        System.out.printf("Virtual root      : %s%n", config.dataDirectory().resolve("vfs").toAbsolutePath().normalize());
        System.out.printf("Event log         : %s%n", config.dataDirectory().resolve("events.jsonl").toAbsolutePath().normalize());
        System.out.printf("Upload quarantine : %s%n", config.dataDirectory().resolve("quarantine").toAbsolutePath().normalize());
        System.out.println("Status            : listening");
        System.out.println();
    }

    private static void closeQuietly(Trap21Server server) {
        try {
            server.close();
        } catch (IOException exception) {
            System.err.println("TRAP21 shutdown failed: " + exception.getMessage());
        }
    }
}
