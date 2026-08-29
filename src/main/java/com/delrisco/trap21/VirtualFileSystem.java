package com.delrisco.trap21;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

final class VirtualFileSystem {
    record Entry(String name, boolean directory, long size, Instant modified) {
    }

    record Capture(Path file, long size, String sha256) {
    }

    static final class UploadTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        UploadTooLargeException() {
            super("Upload exceeds the per-file limit");
        }
    }

    static final class QuarantineFullException extends IOException {
        private static final long serialVersionUID = 1L;

        QuarantineFullException() {
            super("Quarantine capacity exceeded");
        }
    }

    private final Path quarantine;
    private final long maxUploadBytes;
    private final long maxQuarantineBytes;
    private final Map<String, byte[]> files = Map.of(
            "/pub/README.txt", text("Managed File Transfer Gateway\r\nUse assigned credentials for partner transfers.\r\n"),
            "/pub/partners.txt", text("northwind\r\ncontoso\r\nfabrikam\r\n"),
            "/incoming/INSTRUCTIONS.txt", text("Upload partner files to this directory.\r\n"));
    private final Set<String> directories = Set.of("/", "/pub", "/incoming");
    private final Instant seededAt = Instant.now();
    private long quarantineBytes;

    VirtualFileSystem(Path dataDir, long maxUploadBytes, long maxQuarantineBytes) throws IOException {
        this.quarantine = dataDir.resolve("quarantine").toAbsolutePath().normalize();
        this.maxUploadBytes = maxUploadBytes;
        this.maxQuarantineBytes = maxQuarantineBytes;
        Files.createDirectories(quarantine);
        rejectSymbolicLinks(quarantine);
        quarantineBytes = currentSize(quarantine);
    }

    String resolve(String currentDirectory, String argument) throws IOException {
        String value = argument == null || argument.isBlank() ? currentDirectory : argument.trim();
        String raw = value.startsWith("/") ? value : currentDirectory + "/" + value;
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : raw.replace('\\', '/').split("/+")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            validateSegment(segment);
            segments.addLast(segment);
        }
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    boolean isDirectory(String path) {
        return directories.contains(path);
    }

    boolean exists(String path) {
        return directories.contains(path) || files.containsKey(path);
    }

    List<Entry> list(String path) throws IOException {
        if (!exists(path)) {
            throw new IOException("Path not found");
        }
        if (!isDirectory(path)) {
            return List.of(entry(path));
        }
        String prefix = "/".equals(path) ? "/" : path + "/";
        List<Entry> result = new ArrayList<>();
        for (String directory : directories) {
            if (!directory.equals(path) && parent(directory).equals(path)) {
                result.add(new Entry(name(directory), true, 0, seededAt));
            }
        }
        for (String file : files.keySet()) {
            if (file.startsWith(prefix) && parent(file).equals(path)) {
                result.add(entry(file));
            }
        }
        return result.stream().sorted(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    byte[] read(String path) throws IOException {
        byte[] content = files.get(path);
        if (content == null) {
            throw new IOException("File not found");
        }
        return content;
    }

    long size(String path) throws IOException {
        return read(path).length;
    }

    boolean canStore(String path) {
        return path.startsWith("/incoming/") && path.length() > "/incoming/".length();
    }

    synchronized Capture capture(String sessionId, String virtualPath, InputStream input) throws IOException {
        if (!canStore(virtualPath)) {
            throw new IOException("Uploads are only allowed in /incoming");
        }
        String originalName = name(virtualPath);
        validateSegment(originalName);
        Path sessionDir = quarantine.resolve(sessionId).normalize();
        requireInside(quarantine, sessionDir);
        Files.createDirectories(sessionDir);
        rejectSymbolicLinks(sessionDir);

        Path destination = sessionDir.resolve(UUID.randomUUID() + "_" + safeName(originalName)).normalize();
        requireInside(sessionDir, destination);
        MessageDigest digest = sha256();
        long total = 0;
        try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxUploadBytes) {
                    throw new UploadTooLargeException();
                }
                if (quarantineBytes + total > maxQuarantineBytes) {
                    throw new QuarantineFullException();
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(destination);
            throw exception;
        }
        quarantineBytes += total;
        return new Capture(destination, total, HexFormat.of().formatHex(digest.digest()));
    }

    private Entry entry(String path) {
        byte[] content = files.get(path);
        return new Entry(name(path), false, content == null ? 0 : content.length, seededAt);
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private static String name(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static void validateSegment(String value) throws IOException {
        if (value.isBlank() || value.length() > 255 || value.indexOf('\0') >= 0) {
            throw new IOException("Invalid FTP path");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == ':' || c == '\\') {
                throw new IOException("Invalid FTP path");
            }
        }
    }

    private static String safeName(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < 80; i++) {
            char c = value.charAt(i);
            result.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return result.isEmpty() ? "upload.bin" : result.toString();
    }

    private static long currentSize(Path root) throws IOException {
        long total = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                total = Math.addExact(total, Files.size(path));
            }
        }
        return total;
    }

    private static void rejectSymbolicLinks(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            if (paths.anyMatch(Files::isSymbolicLink)) {
                throw new IOException("Symbolic links are not allowed in quarantine");
            }
        }
    }

    private static void requireInside(Path root, Path candidate) throws IOException {
        if (!candidate.startsWith(root)) {
            throw new IOException("Path escapes quarantine");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
