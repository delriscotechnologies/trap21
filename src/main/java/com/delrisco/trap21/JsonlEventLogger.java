package com.delrisco.trap21;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class JsonlEventLogger implements AutoCloseable {
    private static final long MAX_LOG_BYTES = 16L * 1024 * 1024;

    private final Path file;
    private BufferedWriter writer;
    private long bytes;
    private boolean closed;

    JsonlEventLogger(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        Files.createDirectories(this.file.getParent());
        open();
    }

    synchronized boolean log(String type, Map<String, ?> values) {
        if (closed) {
            return false;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("eventType", type);
        event.putAll(values);
        String line = json(event);
        try {
            long lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (bytes > 0 && bytes + lineBytes > MAX_LOG_BYTES) {
                rotate();
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
            bytes += lineBytes;
            return true;
        } catch (IOException exception) {
            System.err.println("TRAP21 log write failed: " + exception.getMessage());
            return false;
        }
    }

    private void open() throws IOException {
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        bytes = Files.size(file);
    }

    private void rotate() throws IOException {
        writer.close();
        Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
        open();
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            writer.close();
        }
    }

    private static String json(Map<String, ?> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                out.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else {
                out.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return out.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
