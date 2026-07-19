package com.delrisco.trap21;

import java.io.IOException;
import java.io.Reader;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

final class BoundedLineReader {
    private static final int DEFAULT_MAX_SESSION_SECONDS = 120;
    private static final int DEFAULT_MAX_VFS_DIRECTORIES = 4096;
    private static final DirectoryBudget VFS_DIRECTORY_BUDGET = loadDirectoryBudget();

    private final Reader reader;
    private final int maximumLength;
    private final Socket socket;
    private final int idleTimeoutMillis;
    private final long commandTimeoutNanos;
    private final long sessionDeadlineNanos;
    private boolean passSeen;

    BoundedLineReader(
            Reader reader,
            int maximumLength,
            Socket socket,
            int idleTimeoutSeconds,
            int commandTimeoutSeconds) {
        this(reader, maximumLength, socket, idleTimeoutSeconds, commandTimeoutSeconds,
                environmentInteger("TRAP21_MAX_SESSION_SECONDS", DEFAULT_MAX_SESSION_SECONDS));
    }

    BoundedLineReader(
            Reader reader,
            int maximumLength,
            Socket socket,
            int idleTimeoutSeconds,
            int commandTimeoutSeconds,
            int maximumSessionSeconds) {
        if (maximumSessionSeconds < 1) {
            throw new IllegalArgumentException("maximumSessionSeconds must be positive");
        }
        this.reader = reader;
        this.maximumLength = maximumLength;
        this.socket = socket;
        this.idleTimeoutMillis = Math.multiplyExact(idleTimeoutSeconds, 1000);
        this.commandTimeoutNanos = TimeUnit.SECONDS.toNanos(commandTimeoutSeconds);
        this.sessionDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(maximumSessionSeconds);
    }

    String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        socket.setSoTimeout(readTimeoutMillis(sessionDeadlineNanos));
        int value = reader.read();
        if (value == -1) {
            return null;
        }
        long commandDeadline = System.nanoTime() + commandTimeoutNanos;
        while (true) {
            if (value == '\n') {
                return validateCommand(line.toString());
            }
            if (value == '\r') {
                int terminator = readBefore(commandDeadline);
                if (terminator == '\n') {
                    return validateCommand(line.toString());
                }
                throw new IOException("FTP commands must end with CRLF");
            }
            if (line.length() >= maximumLength) {
                throw new IOException("FTP command exceeds " + maximumLength + " characters");
            }
            line.append((char) value);
            value = readBefore(commandDeadline);
            if (value == -1) {
                return validateCommand(line.toString());
            }
        }
    }

    private int readBefore(long commandDeadline) throws IOException {
        long deadline = Math.min(commandDeadline, sessionDeadlineNanos);
        socket.setSoTimeout(readTimeoutMillis(deadline));
        return reader.read();
    }

    private int readTimeoutMillis(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("FTP session deadline exceeded");
        }
        long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
        return (int) Math.min(Integer.MAX_VALUE, Math.min(idleTimeoutMillis, remainingMillis));
    }

    private String validateCommand(String line) throws IOException {
        int separator = line.indexOf(' ');
        String command = (separator < 0 ? line : line.substring(0, separator))
                .trim()
                .toUpperCase(Locale.ROOT);
        String argument = separator < 0 ? "" : line.substring(separator + 1).trim();

        if ("USER".equals(command)) {
            passSeen = false;
        } else if ("PASS".equals(command)) {
            if (passSeen) {
                throw new IOException("A repeated PASS command requires a new USER command");
            }
            passSeen = true;
        }

        if (isWritePathCommand(command) && containsHiddenPathSegment(argument)) {
            throw new IOException("Dot-prefixed FTP path segments are not accepted");
        }
        if (("MKD".equals(command) || "XMKD".equals(command)) && !VFS_DIRECTORY_BUDGET.reserve()) {
            throw new IOException("Virtual filesystem directory limit exceeded");
        }
        return line;
    }

    private static boolean isWritePathCommand(String command) {
        return switch (command) {
            case "STOR", "APPE", "MKD", "XMKD", "RNTO" -> true;
            default -> false;
        };
    }

    private static boolean containsHiddenPathSegment(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String segment : path.replace('\\', '/').split("/+")) {
            if (segment.startsWith(".") && !".".equals(segment) && !"..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static DirectoryBudget loadDirectoryBudget() {
        int maximum = environmentInteger("TRAP21_MAX_VFS_DIRECTORIES", DEFAULT_MAX_VFS_DIRECTORIES);
        Path root = Path.of(System.getenv().getOrDefault("TRAP21_DATA_DIR", "data"))
                .toAbsolutePath()
                .normalize()
                .resolve("vfs");
        int existing = 0;
        if (Files.isDirectory(root)) {
            try (Stream<Path> paths = Files.walk(root)) {
                long count = paths.filter(path -> !path.equals(root)).filter(Files::isDirectory).count();
                existing = (int) Math.min(Integer.MAX_VALUE, count);
            } catch (IOException exception) {
                existing = maximum;
            }
        }
        return new DirectoryBudget(existing, maximum);
    }

    private static int environmentInteger(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    static final class DirectoryBudget {
        private final AtomicInteger directories;
        private final int maximum;

        DirectoryBudget(int existing, int maximum) {
            if (existing < 0 || maximum < 1) {
                throw new IllegalArgumentException("Directory limits must be positive");
            }
            this.directories = new AtomicInteger(existing);
            this.maximum = maximum;
        }

        boolean reserve() {
            while (true) {
                int current = directories.get();
                if (current >= maximum) {
                    return false;
                }
                if (directories.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }
    }
}
