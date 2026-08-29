package com.delrisco.trap21;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class Trap21Server implements AutoCloseable {
    static final int CONTROL_PORT = 2121;
    static final int PASSIVE_START = 30000;
    static final int PASSIVE_END = 30009;
    static final int IDLE_TIMEOUT_SECONDS = 120;
    static final int DATA_TIMEOUT_SECONDS = 15;
    static final int MAX_SESSIONS = 32;
    static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    static final long MAX_QUARANTINE_BYTES = 256L * 1024 * 1024;

    private final InetAddress bindAddress;
    private final String publicHost;
    private final VirtualFileSystem fileSystem;
    private final JsonlEventLogger logger;
    private final ExecutorService sessions = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(MAX_SESSIONS);
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();
    private ServerSocket listener;
    private Thread acceptThread;

    Trap21Server(InetAddress bindAddress, String publicHost, Path dataDir) throws IOException {
        this.bindAddress = bindAddress;
        this.publicHost = publicHost;
        this.fileSystem = new VirtualFileSystem(dataDir, MAX_UPLOAD_BYTES, MAX_QUARANTINE_BYTES);
        this.logger = new JsonlEventLogger(dataDir.resolve("events.jsonl"));
    }

    synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("TRAP21 is already running");
        }
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(bindAddress, CONTROL_PORT));
            if (!logger.log("SERVER_STARTED", java.util.Map.of("port", port()))) {
                throw new IOException("Telemetry is unavailable");
            }
            acceptThread = Thread.ofPlatform().name("trap21-listener").start(this::acceptLoop);
        } catch (IOException exception) {
            running.set(false);
            listener.close();
            throw exception;
        }
    }

    int port() {
        return listener == null ? -1 : listener.getLocalPort();
    }

    void awaitTermination() throws InterruptedException {
        Thread thread = acceptThread;
        if (thread != null) {
            thread.join();
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = listener.accept();
                if (!capacity.tryAcquire()) {
                    rejectBusy(socket);
                    continue;
                }
                sockets.add(socket);
                sessions.submit(() -> runSession(socket));
            } catch (IOException exception) {
                if (running.get()) {
                    logger.log("LISTENER_FAILURE", java.util.Map.of("message", String.valueOf(exception.getMessage())));
                }
            }
        }
    }

    private void runSession(Socket socket) {
        try (socket) {
            new ClientSession(bindAddress, publicHost, socket, fileSystem, logger).run();
        } catch (Exception exception) {
            logger.log("SESSION_FAILURE", java.util.Map.of("message", String.valueOf(exception.getMessage())));
        } finally {
            sockets.remove(socket);
            capacity.release();
        }
    }

    private void rejectBusy(Socket socket) {
        try (socket; BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            out.write("421 Too many sessions.\r\n");
            out.flush();
        } catch (IOException ignored) {
            // Peer may disconnect before the response is sent.
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!running.getAndSet(false)) {
            return;
        }
        IOException failure = null;
        try {
            if (listener != null) {
                listener.close();
            }
        } catch (IOException exception) {
            failure = exception;
        }
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best-effort shutdown.
            }
        }
        sessions.shutdown();
        try {
            if (!sessions.awaitTermination(5, TimeUnit.SECONDS)) {
                sessions.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sessions.shutdownNow();
        }
        logger.log("SERVER_STOPPED", java.util.Map.of());
        logger.close();
        if (failure != null) {
            throw failure;
        }
    }
}
