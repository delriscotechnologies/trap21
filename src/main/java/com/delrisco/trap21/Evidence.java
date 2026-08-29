package com.delrisco.trap21;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

final class Evidence {
    static final long MAX_UPLOAD = 10L * 1024 * 1024, MAX_QUARANTINE = 256L * 1024 * 1024;
    private static final int MAX_FILES = 4096;
    private static final long MAX_LOG = 16L * 1024 * 1024;
    private final Path data, quarantine, log;
    private final Object quotaLock = new Object();
    private long quarantineBytes;
    private int quarantineFiles;

    Evidence(Path dataDir) throws IOException {
        data = dataDir.toAbsolutePath().normalize();
        quarantine = data.resolve("quarantine");
        log = data.resolve("events.jsonl");
        Files.createDirectories(quarantine);
        if (Files.isSymbolicLink(quarantine)) throw new IOException("Quarantine cannot be a symbolic link");
        try (Stream<Path> files = Files.walk(quarantine)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) { quarantineBytes += Files.size(file); quarantineFiles++; }
        }
    }

    synchronized void log(String type, Object... kv) {
        try {
            StringBuilder json = new StringBuilder("{\"timestamp\":\"").append(Instant.now())
                    .append("\",\"eventType\":\"").append(escape(type)).append('"');
            for (int i = 0; i + 1 < kv.length; i += 2) {
                Object value = kv[i + 1];
                json.append(",\"").append(escape(String.valueOf(kv[i]))).append("\":");
                if (value instanceof Number || value instanceof Boolean) json.append(value);
                else json.append('"').append(escape(String.valueOf(value))).append('"');
            }
            json.append("}\n");
            long added = json.toString().getBytes(StandardCharsets.UTF_8).length;
            if (Files.exists(log) && Files.size(log) + added > MAX_LOG)
                Files.move(log, data.resolve("events.jsonl.1"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(log, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("TRAP21 log write failed: " + e.getMessage());
        }
    }

    Capture capture(String sessionId, String name, InputStream in) throws Exception {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) safe = "upload.bin";
        if (safe.length() > 80) safe = safe.substring(0, 80);
        Path dir = quarantine.resolve(sessionId);
        Path file = dir.resolve(UUID.randomUUID() + "_" + safe);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0, reserved = 0;
        synchronized (quotaLock) {
            if (quarantineFiles >= MAX_FILES) throw new LimitException("QUARANTINE_LIMIT");
            quarantineFiles++;
        }
        try {
            Files.createDirectories(dir);
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[16 * 1024];
            for (int n; (n = in.read(buffer)) != -1;) {
                if (size + n > MAX_UPLOAD) throw new LimitException("UPLOAD_LIMIT");
                synchronized (quotaLock) {
                    if (quarantineBytes + n > MAX_QUARANTINE) throw new LimitException("QUARANTINE_LIMIT");
                    quarantineBytes += n; reserved += n;
                }
                size += n; digest.update(buffer, 0, n); out.write(buffer, 0, n);
            }
            }
        } catch (Exception e) {
            synchronized (quotaLock) { quarantineBytes -= reserved; quarantineFiles--; }
            Files.deleteIfExists(file);
            try { Files.deleteIfExists(dir); } catch (DirectoryNotEmptyException ignored) {}
            throw e;
        }
        return new Capture(file, size, HexFormat.of().formatHex(digest.digest()));
    }

    record Capture(Path file, long size, String sha256) {}
    static final class LimitException extends IOException {
        private static final long serialVersionUID = 1L;
        final String reason;
        LimitException(String reason) { this.reason = reason; }
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) switch (c) {
            case '\\' -> out.append("\\\\");
            case '"' -> out.append("\\\"");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
        }
        return out.toString();
    }
}
