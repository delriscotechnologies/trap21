package com.delrisco.trap21;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class JsonlEventLogger implements Closeable {
    private final Path file;
    private final long maximumBytes;
    private final int maximumArchives;
    private BufferedWriter writer;
    private long currentBytes;

    JsonlEventLogger(Path file, long maximumBytes, int maximumArchives) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        this.maximumBytes = maximumBytes;
        this.maximumArchives = maximumArchives;
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        this.writer = openWriter();
        this.currentBytes = Files.size(this.file);
        hardenPermissions();
    }

    private BufferedWriter openWriter() throws IOException {
        return Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    synchronized void log(String eventType, Map<String, ?> values) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("eventType", eventType);
        event.putAll(values);
        try {
            String encoded = toJson(event);
            long eventBytes = encoded.getBytes(StandardCharsets.UTF_8).length
                    + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
            if (currentBytes > 0 && currentBytes + eventBytes > maximumBytes) {
                rotate();
            }
            writer.write(encoded);
            writer.newLine();
            writer.flush();
            currentBytes += eventBytes;
        } catch (IOException exception) {
            System.err.println("TRAP21 log write failed: " + exception.getMessage());
        }
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }

    private void rotate() throws IOException {
        writer.close();
        Files.deleteIfExists(archive(maximumArchives));
        for (int index = maximumArchives - 1; index >= 1; index--) {
            Path source = archive(index);
            if (Files.exists(source)) {
                Files.move(source, archive(index + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (Files.exists(file)) {
            Files.move(file, archive(1), StandardCopyOption.REPLACE_EXISTING);
        }
        writer = openWriter();
        currentBytes = 0;
        hardenPermissions();
    }

    private Path archive(int index) {
        return file.resolveSibling(file.getFileName() + "." + index);
    }

    private void hardenPermissions() throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows and some mounted filesystems use ACLs instead of POSIX permissions.
        }
    }

    private static String toJson(Map<String, ?> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                json.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value);
            } else {
                json.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
