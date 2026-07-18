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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

final class JsonlEventLogger implements Closeable {
    private static final int MAX_COMMAND_EVENTS_PER_SECOND = 100;
    private static final long COMMAND_WINDOW_MILLIS = 1_000L;

    private final Path file;
    private final long maximumBytes;
    private final int maximumArchives;
    private final Map<String, CommandWindow> commandWindows = new HashMap<>();
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
        Instant now = Instant.now();
        try {
            if ("COMMAND".equals(eventType) && suppressCommandEvent(now, values)) {
                return;
            }
            if ("DISCONNECT".equals(eventType)) {
                flushCommandWindow(now, values.get("sessionId"));
            }
            writeEvent(now, eventType, values);
        } catch (IOException exception) {
            System.err.println("TRAP21 log write failed: " + exception.getMessage());
        }
    }

    @Override
    public synchronized void close() throws IOException {
        Instant now = Instant.now();
        for (CommandWindow window : commandWindows.values()) {
            writeSuppressionSummary(now, window);
        }
        commandWindows.clear();
        writer.close();
    }

    private boolean suppressCommandEvent(Instant now, Map<String, ?> values) throws IOException {
        Object sessionValue = values.get("sessionId");
        if (sessionValue == null) {
            return false;
        }

        String sessionId = String.valueOf(sessionValue);
        long nowMillis = now.toEpochMilli();
        CommandWindow window = commandWindows.computeIfAbsent(
                sessionId,
                ignored -> new CommandWindow(nowMillis));
        if (nowMillis - window.startedAtMillis >= COMMAND_WINDOW_MILLIS) {
            writeSuppressionSummary(now, window);
            window.reset(nowMillis);
        }
        window.captureContext(values);
        if (window.loggedEvents < MAX_COMMAND_EVENTS_PER_SECOND) {
            window.loggedEvents++;
            return false;
        }
        window.suppressedEvents++;
        return true;
    }

    private void flushCommandWindow(Instant now, Object sessionValue) throws IOException {
        if (sessionValue == null) {
            return;
        }
        CommandWindow window = commandWindows.remove(String.valueOf(sessionValue));
        if (window != null) {
            writeSuppressionSummary(now, window);
        }
    }

    private void writeSuppressionSummary(Instant now, CommandWindow window) throws IOException {
        if (window.suppressedEvents == 0) {
            return;
        }
        Map<String, Object> summary = new LinkedHashMap<>(window.context);
        summary.put("suppressed", window.suppressedEvents);
        summary.put("limitPerSecond", MAX_COMMAND_EVENTS_PER_SECOND);
        summary.put("windowMillis", COMMAND_WINDOW_MILLIS);
        writeEvent(now, "COMMANDS_SUPPRESSED", summary);
    }

    private void writeEvent(Instant timestamp, String eventType, Map<String, ?> values) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", timestamp.toString());
        event.put("eventType", eventType);
        event.putAll(values);
        event.remove("passwordRank");

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

    private static final class CommandWindow {
        private final Map<String, Object> context = new LinkedHashMap<>();
        private long startedAtMillis;
        private int loggedEvents;
        private long suppressedEvents;

        CommandWindow(long startedAtMillis) {
            this.startedAtMillis = startedAtMillis;
        }

        void captureContext(Map<String, ?> values) {
            copy(values, "sessionId");
            copy(values, "sourceIp");
            copy(values, "sourcePort");
            copy(values, "username");
        }

        void reset(long nowMillis) {
            startedAtMillis = nowMillis;
            loggedEvents = 0;
            suppressedEvents = 0;
            context.clear();
        }

        private void copy(Map<String, ?> values, String key) {
            if (values.containsKey(key)) {
                context.put(key, values.get(key));
            }
        }
    }
}
