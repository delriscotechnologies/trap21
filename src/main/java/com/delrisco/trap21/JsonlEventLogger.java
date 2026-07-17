package com.delrisco.trap21;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class JsonlEventLogger implements Closeable {
    private final BufferedWriter writer;

    JsonlEventLogger(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        this.writer = Files.newBufferedWriter(
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
            writer.write(toJson(event));
            writer.newLine();
            writer.flush();
        } catch (IOException exception) {
            System.err.println("TRAP21 log write failed: " + exception.getMessage());
        }
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
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
