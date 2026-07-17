package com.delrisco.trap21;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    static final class StorageQuotaExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        StorageQuotaExceededException(long maximumBytes, int maximumFiles) {
            super("Quarantine capacity is limited to " + maximumBytes + " bytes and " + maximumFiles + " files");
        }
    }

    private final Path root;
    private final Path quarantine;
    private final long maxQuarantineBytes;
    private final int maxQuarantineFiles;
    private final int retentionDays;
    private final Map<String, CapturedUpload> uploads = new ConcurrentHashMap<>();
    private long quarantineBytes;
    private int quarantineFiles;
    private Instant nextPruneAt = Instant.EPOCH;

    VirtualFileSystem(
            Path dataDirectory,
            long maxQuarantineBytes,
            int maxQuarantineFiles,
            int retentionDays) throws IOException {
        this.maxQuarantineBytes = maxQuarantineBytes;
        this.maxQuarantineFiles = maxQuarantineFiles;
        this.retentionDays = retentionDays;
        Path normalizedData = dataDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedData);
        Path realData = normalizedData.toRealPath();
        this.root = realData.resolve("vfs").normalize();
        this.quarantine = realData.resolve("quarantine").normalize();
        Files.createDirectories(root);
        Files.createDirectories(quarantine);
        requireRealDirectory(root);
        requireRealDirectory(quarantine);
        rejectSymbolicLinksInTree(root);
        rejectSymbolicLinksInTree(quarantine);
        SeedData.populate(root);
        rejectSymbolicLinksInTree(root);
        pruneExpiredQuarantine(true);
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

    boolean exists(String virtualPath) throws IOException {
        return uploads.containsKey(virtualPath)
                || Files.exists(toHostPath(virtualPath), LinkOption.NOFOLLOW_LINKS);
    }

    boolean isDirectory(String virtualPath) throws IOException {
        return Files.isDirectory(toHostPath(virtualPath), LinkOption.NOFOLLOW_LINKS);
    }

    void validatePath(String virtualPath) throws IOException {
        toHostPath(virtualPath);
    }

    List<FileEntry> list(String virtualPath, boolean includeHidden) throws IOException {
        Path hostPath = toHostPath(virtualPath);
        if (!Files.exists(hostPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(virtualPath);
        }
        if (!Files.isDirectory(hostPath, LinkOption.NOFOLLOW_LINKS)) {
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
                    entries.put(name, entry(childVirtual, toHostPath(childVirtual)));
                } catch (IOException exception) {
                    // Removed entries and symbolic links are omitted from the decoy listing.
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
        Path source = captured == null
                ? toHostPath(virtualPath)
                : checkedQuarantinePath(captured.quarantineFile());
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(virtualPath);
        }
        return Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
    }

    long size(String virtualPath) throws IOException {
        CapturedUpload captured = uploads.get(virtualPath);
        if (captured != null) {
            return captured.size();
        }
        Path path = toHostPath(virtualPath);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(virtualPath);
        }
        return Files.size(path);
    }

    Instant modified(String virtualPath) throws IOException {
        CapturedUpload captured = uploads.get(virtualPath);
        return captured != null
                ? captured.capturedAt()
                : Files.getLastModifiedTime(toHostPath(virtualPath), LinkOption.NOFOLLOW_LINKS).toInstant();
    }

    synchronized CapturedUpload capture(
            String sessionId,
            String virtualPath,
            InputStream input,
            long maximumBytes,
            boolean append) throws IOException {
        pruneExpiredQuarantine(false);
        if (quarantineFiles >= maxQuarantineFiles || quarantineBytes >= maxQuarantineBytes) {
            throw new StorageQuotaExceededException(maxQuarantineBytes, maxQuarantineFiles);
        }
        long remainingQuarantineBytes = maxQuarantineBytes - quarantineBytes;
        Path visiblePath = toHostPath(virtualPath);
        Path parent = visiblePath.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(parent(virtualPath));
        }
        if (Files.isDirectory(visiblePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(virtualPath);
        }
        CapturedUpload previous = uploads.get(virtualPath);
        boolean visibleExists = Files.exists(visiblePath, LinkOption.NOFOLLOW_LINKS);
        if (visibleExists && !append && previous == null) {
            throw new FileAlreadyExistsException("Refusing to replace a seeded decoy: " + virtualPath);
        }

        Path appendSource = null;
        if (append && previous != null) {
            appendSource = checkedQuarantinePath(previous.quarantineFile());
        } else if (append && visibleExists) {
            appendSource = visiblePath;
        }

        String originalName = fileName(virtualPath);
        validateFileName(originalName);
        Path sessionDirectory = quarantine.resolve(safeSegment(sessionId)).normalize();
        assertInside(quarantine, sessionDirectory);
        Files.createDirectories(sessionDirectory);
        assertNoSymbolicLinkComponents(quarantine, sessionDirectory);
        Path capturedPath = sessionDirectory.resolve(UUID.randomUUID() + "_" + safeSegment(originalName)).normalize();
        assertInside(sessionDirectory, capturedPath);

        MessageDigest digest = sha256();
        long total = 0;
        try (OutputStream output = Files.newOutputStream(
                capturedPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            if (appendSource != null) {
                try (InputStream existing = Files.newInputStream(appendSource, LinkOption.NOFOLLOW_LINKS)) {
                    total = copyLimited(
                            existing, output, digest, total, maximumBytes, remainingQuarantineBytes,
                            maxQuarantineBytes, maxQuarantineFiles);
                }
            }
            total = copyLimited(
                    input, output, digest, total, maximumBytes, remainingQuarantineBytes,
                    maxQuarantineBytes, maxQuarantineFiles);
        } catch (IOException exception) {
            Files.deleteIfExists(capturedPath);
            throw exception;
        }

        try {
            if (!visibleExists) {
                Files.createFile(visiblePath);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(capturedPath);
            throw exception;
        }
        CapturedUpload upload = new CapturedUpload(
                virtualPath,
                capturedPath,
                originalName,
                total,
                HexFormat.of().formatHex(digest.digest()),
                Instant.now());
        uploads.put(virtualPath, upload);
        quarantineBytes += total;
        quarantineFiles++;
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
        if (!Files.exists(visible, LinkOption.NOFOLLOW_LINKS)
                || Files.isDirectory(visible, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(virtualPath);
        }
        Files.delete(visible);
    }

    synchronized void rename(String sourceVirtual, String targetVirtual) throws IOException {
        Path source = toHostPath(sourceVirtual);
        Path target = toHostPath(targetVirtual);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(sourceVirtual);
        }
        if (!Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(parent(targetVirtual));
        }
        validateFileName(fileName(targetVirtual));
        boolean directory = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        String descendantPrefix = sourceVirtual + "/";
        Map<String, CapturedUpload> moved = new LinkedHashMap<>();
        for (Map.Entry<String, CapturedUpload> entry : uploads.entrySet()) {
            String path = entry.getKey();
            if (path.equals(sourceVirtual) || directory && path.startsWith(descendantPrefix)) {
                String suffix = path.substring(sourceVirtual.length());
                String movedPath = targetVirtual + suffix;
                CapturedUpload captured = entry.getValue();
                moved.put(movedPath, new CapturedUpload(
                        movedPath,
                        captured.quarantineFile(),
                        fileName(movedPath),
                        captured.size(),
                        captured.sha256(),
                        captured.capturedAt()));
                uploads.remove(path, captured);
            }
        }
        uploads.putAll(moved);
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
        if (Files.isSymbolicLink(hostPath)) {
            throw new IOException("Symbolic links are not allowed in the virtual filesystem");
        }
        CapturedUpload captured = uploads.get(virtualPath);
        boolean directory = Files.isDirectory(hostPath, LinkOption.NOFOLLOW_LINKS);
        long size = directory ? 0L : captured == null ? Files.size(hostPath) : captured.size();
        Instant modified = captured == null
                ? Files.getLastModifiedTime(hostPath, LinkOption.NOFOLLOW_LINKS).toInstant()
                : captured.capturedAt();
        return new FileEntry(fileName(virtualPath), virtualPath, directory, size, modified);
    }

    private Path toHostPath(String virtualPath) throws IOException {
        String normalized = normalizeVirtualPath(virtualPath);
        Path candidate = "/".equals(normalized)
                ? root
                : root.resolve(normalized.substring(1)).normalize();
        assertInside(root, candidate);
        assertNoSymbolicLinkComponents(root, candidate);
        return candidate;
    }

    private static void assertInside(Path expectedRoot, Path candidate) throws IOException {
        if (!candidate.startsWith(expectedRoot)) {
            throw new IOException("Path escapes the virtual filesystem");
        }
    }

    private Path checkedQuarantinePath(Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        assertInside(quarantine, normalized);
        assertNoSymbolicLinkComponents(quarantine, normalized);
        return normalized;
    }

    private static void requireRealDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("TRAP21 data directories must be real directories, not symbolic links: " + directory);
        }
    }

    private static void rejectSymbolicLinksInTree(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            Path symbolicLink = paths.filter(Files::isSymbolicLink).findFirst().orElse(null);
            if (symbolicLink != null) {
                throw new IOException("Symbolic links are not allowed in TRAP21 data: " + symbolicLink);
            }
        }
    }

    private static void assertNoSymbolicLinkComponents(Path expectedRoot, Path candidate) throws IOException {
        Path current = expectedRoot;
        if (Files.isSymbolicLink(current)) {
            throw new IOException("Symbolic links are not allowed in TRAP21 data: " + current);
        }
        for (Path segment : expectedRoot.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not allowed in TRAP21 data: " + current);
            }
        }
    }

    private static long copyLimited(
            InputStream input,
            OutputStream output,
            MessageDigest digest,
            long initialBytes,
            long maximumBytes,
            long remainingQuarantineBytes,
            long maximumQuarantineBytes,
            int maximumQuarantineFiles) throws IOException {
        long total = initialBytes;
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maximumBytes) {
                throw new UploadTooLargeException(maximumBytes);
            }
            if (total > remainingQuarantineBytes) {
                throw new StorageQuotaExceededException(maximumQuarantineBytes, maximumQuarantineFiles);
            }
            digest.update(buffer, 0, count);
            output.write(buffer, 0, count);
        }
        return total;
    }

    private void pruneExpiredQuarantine(boolean force) throws IOException {
        Instant now = Instant.now();
        if (!force && now.isBefore(nextPruneAt)) {
            return;
        }
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        Set<Path> activeCaptures = uploads.values().stream()
                .map(CapturedUpload::quarantineFile)
                .map(path -> path.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        try (Stream<Path> paths = Files.walk(quarantine)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!activeCaptures.contains(normalized)
                        && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(path);
                }
            }
        }
        try (Stream<Path> paths = Files.walk(quarantine)) {
            for (Path directory : paths
                    .filter(path -> !path.equals(quarantine))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                try {
                    Files.deleteIfExists(directory);
                } catch (DirectoryNotEmptyException ignored) {
                    // Retained artifacts keep their session directory.
                }
            }
        }
        refreshQuarantineUsage();
        nextPruneAt = now.plus(Duration.ofHours(1));
    }

    private void refreshQuarantineUsage() throws IOException {
        long bytes = 0;
        int files = 0;
        try (Stream<Path> paths = Files.walk(quarantine)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                bytes = Math.addExact(bytes, Files.size(path));
                files = Math.addExact(files, 1);
            }
        }
        quarantineBytes = bytes;
        quarantineFiles = files;
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
