package com.delrisco.trap21;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

final class VirtualFileSystem {
    record FileEntry(String name, String virtualPath, boolean directory, long size, Instant modified) {
    }

    record CapturedUpload(
            String virtualPath,
            Path quarantineFile,
            String originalName,
            long size,
            String sha256,
            Instant capturedAt) {
    }

    static final class UploadTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        UploadTooLargeException(long maximum) {
            super("Upload exceeds the configured limit of " + maximum + " bytes");
        }
    }

    private final Path root;
    private final Path quarantine;
    private final Map<String, CapturedUpload> uploads = new ConcurrentHashMap<>();

    VirtualFileSystem(Path dataDirectory) throws IOException {
        Path normalizedData = dataDirectory.toAbsolutePath().normalize();
        this.root = normalizedData.resolve("vfs").normalize();
        this.quarantine = normalizedData.resolve("quarantine").normalize();
        Files.createDirectories(root);
        Files.createDirectories(quarantine);
        SeedData.populate(root);
    }

    Path root() {
        return root;
    }

    Path quarantine() {
        return quarantine;
    }

    String resolve(String currentDirectory, String argument) throws IOException {
        String value = argument == null || argument.isBlank() ? currentDirectory : argument.trim();
        String raw = value.startsWith("/") ? value : currentDirectory + "/" + value;
        return normalizeVirtualPath(raw);
    }

    boolean exists(String virtualPath) {
        return uploads.containsKey(virtualPath) || Files.exists(toHostPathUnchecked(virtualPath));
    }

    boolean isDirectory(String virtualPath) {
        return Files.isDirectory(toHostPathUnchecked(virtualPath));
    }

    List<FileEntry> list(String virtualPath, boolean includeHidden) throws IOException {
        Path hostPath = toHostPath(virtualPath);
        if (!Files.exists(hostPath)) {
            throw new NoSuchFileException(virtualPath);
        }
        if (!Files.isDirectory(hostPath)) {
            return List.of(entry(virtualPath, hostPath));
        }

        Map<String, FileEntry> entries = new LinkedHashMap<>();
        try (Stream<Path> children = Files.list(hostPath)) {
            children.forEach(child -> {
                String name = child.getFileName().toString();
                if (!includeHidden && name.startsWith(".")) {
                    return;
                }
                String childVirtual = childVirtualPath(virtualPath, name);
                try {
                    entries.put(name, entry(childVirtual, child));
                } catch (IOException exception) {
                    // A concurrently removed decoy is simply omitted from this listing.
                }
            });
        }

        for (CapturedUpload upload : uploads.values()) {
            if (parent(upload.virtualPath()).equals(virtualPath)) {
                String name = fileName(upload.virtualPath());
                entries.put(name, new FileEntry(name, upload.virtualPath(), false, upload.size(), upload.capturedAt()));
            }
        }

        return entries.values().stream()
                .sorted(Comparator.comparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    InputStream openForRead(String virtualPath) throws IOException {
        CapturedUpload captured = uploads.get(virtualPath);
        Path source = captured == null ? toHostPath(virtualPath) : captured.quarantineFile();
        if (!Files.exists(source) || Files.isDirectory(source)) {
            throw new NoSuchFileException(virtualPath);
        }
        return Files.newInputStream(source);
    }

    long size(String virtualPath) throws IOException {
        CapturedUpload captured = uploads.get(virtualPath);
        if (captured != null) {
            return captured.size();
        }
        Path path = toHostPath(virtualPath);
        if (!Files.exists(path) || Files.isDirectory(path)) {
            throw new NoSuchFileException(virtualPath);
        }
        return Files.size(path);
    }

    Instant modified(String virtualPath) throws IOException {
        CapturedUpload captured = uploads.get(virtualPath);
        return captured != null
                ? captured.capturedAt()
                : Files.getLastModifiedTime(toHostPath(virtualPath)).toInstant();
    }

    CapturedUpload capture(
            String sessionId,
            String virtualPath,
            InputStream input,
            long maximumBytes) throws IOException {
        Path visiblePath = toHostPath(virtualPath);
        Path parent = visiblePath.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new NoSuchFileException(parent(virtualPath));
        }
        if (Files.isDirectory(visiblePath)) {
            throw new FileAlreadyExistsException(virtualPath);
        }
        if (Files.exists(visiblePath) && !uploads.containsKey(virtualPath)) {
            throw new FileAlreadyExistsException("Refusing to replace a seeded decoy: " + virtualPath);
        }

        String originalName = fileName(virtualPath);
        validateFileName(originalName);
        Path sessionDirectory = quarantine.resolve(safeSegment(sessionId)).normalize();
        assertInside(quarantine, sessionDirectory);
        Files.createDirectories(sessionDirectory);
        Path capturedPath = sessionDirectory.resolve(UUID.randomUUID() + "_" + safeSegment(originalName)).normalize();
        assertInside(sessionDirectory, capturedPath);

        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (OutputStream output = Files.newOutputStream(capturedPath)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) {
                    throw new UploadTooLargeException(maximumBytes);
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(capturedPath);
            throw exception;
        }

        if (!Files.exists(visiblePath)) {
            Files.createFile(visiblePath);
        }
        CapturedUpload upload = new CapturedUpload(
                virtualPath,
                capturedPath,
                originalName,
                total,
                HexFormat.of().formatHex(digest.digest()),
                Instant.now());
        uploads.put(virtualPath, upload);
        return upload;
    }

    void makeDirectory(String virtualPath) throws IOException {
        Files.createDirectory(toHostPath(virtualPath));
    }

    void removeDirectory(String virtualPath) throws IOException {
        if ("/".equals(virtualPath)) {
            throw new DirectoryNotEmptyException(virtualPath);
        }
        Files.delete(toHostPath(virtualPath));
    }

    void delete(String virtualPath) throws IOException {
        uploads.remove(virtualPath);
        Path visible = toHostPath(virtualPath);
        if (!Files.exists(visible) || Files.isDirectory(visible)) {
            throw new NoSuchFileException(virtualPath);
        }
        Files.delete(visible);
    }

    void rename(String sourceVirtual, String targetVirtual) throws IOException {
        Path source = toHostPath(sourceVirtual);
        Path target = toHostPath(targetVirtual);
        if (!Files.exists(source)) {
            throw new NoSuchFileException(sourceVirtual);
        }
        if (!Files.isDirectory(target.getParent())) {
            throw new NoSuchFileException(parent(targetVirtual));
        }
        validateFileName(fileName(targetVirtual));
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        CapturedUpload captured = uploads.remove(sourceVirtual);
        if (captured != null) {
            uploads.put(targetVirtual, new CapturedUpload(
                    targetVirtual,
                    captured.quarantineFile(),
                    fileName(targetVirtual),
                    captured.size(),
                    captured.sha256(),
                    captured.capturedAt()));
        }
    }

    static String normalizeVirtualPath(String raw) throws IOException {
        if (raw == null || raw.indexOf('\0') >= 0) {
            throw new IOException("Invalid FTP path");
        }
        String canonicalSeparators = raw.replace('\\', '/');
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : canonicalSeparators.split("/+")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            validateFileName(segment);
            segments.addLast(segment);
        }
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    private FileEntry entry(String virtualPath, Path hostPath) throws IOException {
        CapturedUpload captured = uploads.get(virtualPath);
        boolean directory = Files.isDirectory(hostPath);
        long size = directory ? 0L : captured == null ? Files.size(hostPath) : captured.size();
        Instant modified = captured == null
                ? Files.getLastModifiedTime(hostPath).toInstant()
                : captured.capturedAt();
        return new FileEntry(fileName(virtualPath), virtualPath, directory, size, modified);
    }

    private Path toHostPath(String virtualPath) throws IOException {
        String normalized = normalizeVirtualPath(virtualPath);
        Path candidate = "/".equals(normalized)
                ? root
                : root.resolve(normalized.substring(1)).normalize();
        assertInside(root, candidate);
        return candidate;
    }

    private Path toHostPathUnchecked(String virtualPath) {
        try {
            return toHostPath(virtualPath);
        } catch (IOException exception) {
            return root.resolve("__invalid_path__");
        }
    }

    private static void assertInside(Path expectedRoot, Path candidate) throws IOException {
        if (!candidate.startsWith(expectedRoot)) {
            throw new IOException("Path escapes the virtual filesystem");
        }
    }

    private static String childVirtualPath(String directory, String name) {
        return "/".equals(directory) ? "/" + name : directory + "/" + name;
    }

    private static String parent(String virtualPath) {
        int separator = virtualPath.lastIndexOf('/');
        return separator <= 0 ? "/" : virtualPath.substring(0, separator);
    }

    private static String fileName(String virtualPath) {
        int separator = virtualPath.lastIndexOf('/');
        return separator < 0 ? virtualPath : virtualPath.substring(separator + 1);
    }

    private static void validateFileName(String name) throws IOException {
        if (name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")) {
            throw new IOException("Invalid FTP path segment");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character < 0x20 || character == '/' || character == '\\' || character == ':') {
                throw new IOException("Invalid FTP path segment");
            }
        }
    }

    private static String safeSegment(String value) {
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < value.length() && safe.length() < 96; index++) {
            char character = value.charAt(index);
            safe.append(Character.isLetterOrDigit(character) || character == '.' || character == '-' || character == '_'
                    ? character
                    : '_');
        }
        return safe.isEmpty() ? "capture" : safe.toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
