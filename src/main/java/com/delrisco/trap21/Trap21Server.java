package com.delrisco.trap21;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Trap21Server implements AutoCloseable {
    private final Trap21Config config;
    private final CredentialStore credentials;
    private final VirtualFileSystem fileSystem;
    private final JsonlEventLogger logger;
    private final ExecutorService sessions;
    private final Semaphore capacity;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> sessionsBySource = new ConcurrentHashMap<>();
    private ServerSocket controlListener;
    private Thread acceptThread;

    public Trap21Server(Trap21Config config) throws IOException {
        this.config = config;
        this.credentials = new CredentialStore();
        this.fileSystem = new VirtualFileSystem(
                config.dataDirectory(),
                config.maxQuarantineBytes(),
                config.maxQuarantineFiles(),
                config.retentionDays());
        this.logger = new JsonlEventLogger(
                config.dataDirectory().resolve("events.jsonl"),
                config.maxEventLogBytes(),
                config.maxEventArchives());
        this.sessions = Executors.newVirtualThreadPerTaskExecutor();
        this.capacity = new Semaphore(config.maxSessions());
    }

    public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("TRAP21 is already running");
        }
        try {
            controlListener = new ServerSocket();
            controlListener.setReuseAddress(true);
            controlListener.bind(new InetSocketAddress(config.bindAddress(), config.controlPort()));
            acceptThread = Thread.ofPlatform().name("trap21-control-listener").start(this::acceptLoop);
            logger.log("SERVER_STARTED", Map.of(
                    "bind", config.bindAddress().getHostAddress(),
                    "port", port(),
                    "passiveStart", config.passivePortStart(),
                    "passiveEnd", config.passivePortEnd(),
                    "virtualRoot", fileSystem.root().toString(),
                    "quarantine", fileSystem.quarantine().toString()));
        } catch (IOException exception) {
            running.set(false);
            throw exception;
        }
    }

    public int port() {
        ServerSocket listener = controlListener;
        return listener == null ? -1 : listener.getLocalPort();
    }

    public void awaitTermination() throws InterruptedException {
        Thread listener = acceptThread;
        if (listener != null) {
            listener.join();
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = controlListener.accept();
                if (!acquireCapacity(socket)) {
                    continue;
                }
                String sourceIp = socket.getInetAddress().getHostAddress();
                activeSockets.add(socket);
                try {
                    sessions.submit(() -> {
                        try (socket) {
                            new ClientSession(config, socket, credentials, fileSystem, logger).run();
                        } catch (Exception exception) {
                            Map<String, Object> event = new LinkedHashMap<>();
                            event.put("error", exception.getClass().getSimpleName());
                            event.put("message", exception.getMessage());
                            logger.log("SESSION_FAILURE", event);
                        } finally {
                            activeSockets.remove(socket);
                            releaseSource(sourceIp);
                            capacity.release();
                        }
                    });
                } catch (RuntimeException exception) {
                    activeSockets.remove(socket);
                    releaseSource(sourceIp);
                    capacity.release();
                    socket.close();
                    if (running.get()) {
                        logger.log("SESSION_REJECTED", Map.of(
                                "reason", "executor",
                                "sourceIp", sourceIp));
                    }
                }
            } catch (IOException exception) {
                if (running.get()) {
                    logger.log("LISTENER_FAILURE", Map.of("message", String.valueOf(exception.getMessage())));
                }
            }
        }
    }

    private boolean acquireCapacity(Socket socket) {
        String sourceIp = socket.getInetAddress().getHostAddress();
        if (!capacity.tryAcquire()) {
            rejectBusy(socket, "capacity", sourceIp);
            return false;
        }
        AtomicInteger sourceSessions = sessionsBySource.computeIfAbsent(sourceIp, ignored -> new AtomicInteger());
        if (sourceSessions.incrementAndGet() > config.maxSessionsPerIp()) {
            releaseSource(sourceIp);
            capacity.release();
            rejectBusy(socket, "source_capacity", sourceIp);
            return false;
        }
        return true;
    }

    private void releaseSource(String sourceIp) {
        sessionsBySource.computeIfPresent(sourceIp, (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    private void rejectBusy(Socket socket, String reason, String sourceIp) {
        try (socket;
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write("421 Service not available, closing control connection.\r\n");
            writer.flush();
        } catch (IOException ignored) {
            // The peer may disconnect before the overload response is written.
        }
        logger.log("SESSION_REJECTED", Map.of("reason", reason, "sourceIp", sourceIp));
    }

    @Override
    public synchronized void close() throws IOException {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        IOException failure = null;
        try {
            if (controlListener != null) {
                controlListener.close();
            }
        } catch (IOException exception) {
            failure = exception;
        }
        sessions.shutdown();
        for (Socket socket : activeSockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing active sessions is best-effort during shutdown.
            }
        }
        try {
            if (!sessions.awaitTermination(5, TimeUnit.SECONDS)) {
                sessions.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sessions.shutdownNow();
        }
        logger.log("SERVER_STOPPED", Map.of());
        try {
            logger.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
