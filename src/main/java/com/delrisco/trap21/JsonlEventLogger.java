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
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class JsonlEventLogger implements Closeable {
    private static final int MAX_COMMAND_EVENTS_PER_SECOND = 100;
    private static final int MAX_FAILED_AUTH_EVENTS_PER_SECOND = 25;
    private static final long EVENT_WINDOW_MILLIS = 1_000L;

    private final Path file;
    private final long maximumBytes;
    private final int maximumArchives;
    private final Clock clock;
    private final Map<String, CommandWindow> commandWindows = new HashMap<>();
    private final Map<String, AuthWindow> authWindows = new HashMap<>();
    private BufferedWriter writer;
    private long currentBytes;

    JsonlEventLogger(Path file, long maximumBytes, int maximumArchives) throws IOException {
        this(file, maximumBytes, maximumArchives, Clock.systemUTC());
    }

    JsonlEventLogger(Path file, long maximumBytes, int maximumArchives, Clock clock) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        this.maximumBytes = maximumBytes;
        this.maximumArchives = maximumArchives;
        this.clock = clock;
        Files.createDirectories(this.file.getParent());
        this.writer = openWriter();
        this.currentBytes = Files.size(this.file);
        hardenPermissions(this.file);
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
        Instant now = clock.instant();
        try {
            if ("COMMAND".equals(eventType) && suppressCommandEvent(now, values)) {
                return;
            }
            if ("AUTH_ATTEMPT".equals(eventType)) {
                if (Boolean.TRUE.equals(values.get("accepted"))) {
                    flushAuthWindow(now, values.get("sessionId"));
                } else if (suppressFailedAuthEvent(now, values)) {
                    return;
                }
            }
            if ("DISCONNECT".equals(eventType)) {
                Object sessionValue = values.get("sessionId");
                flushCommandWindow(now, sessionValue);
                flushAuthWindow(now, sessionValue);
            }
            writeEvent(now, eventType, values);
        } catch (IOException exception) {
            System.err.println("TRAP21 log write failed: " + exception.getMessage());
        }
    }

    @Override
    public synchronized void close() throws IOException {
        Instant now = clock.instant();
        for (CommandWindow window : commandWindows.values()) {
            writeCommandSuppressionSummary(now, window);
        }
        for (AuthWindow window : authWindows.values()) {
            writeAuthSuppressionSummary(now, window);
        }
        commandWindows.clear();
        authWindows.clear();
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
        if (nowMillis - window.startedAtMillis >= EVENT_WINDOW_MILLIS) {
            writeCommandSuppressionSummary(now, window);
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

    private boolean suppressFailedAuthEvent(Instant now, Map<String, ?> values) throws IOException {
        Object sessionValue = values.get("sessionId");
        if (sessionValue == null) {
            return false;
        }

        String sessionId = String.valueOf(sessionValue);
        long nowMillis = now.toEpochMilli();
        AuthWindow window = authWindows.computeIfAbsent(
                sessionId,
                ignored -> new AuthWindow(nowMillis));
        if (nowMillis - window.startedAtMillis >= EVENT_WINDOW_MILLIS) {
            writeAuthSuppressionSummary(now, window);
            window.reset(nowMillis);
        }
        window.observe(values);
        if (window.loggedEvents < MAX_FAILED_AUTH_EVENTS_PER_SECOND) {
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
            writeCommandSuppressionSummary(now, window);
        }
    }

    private void flushAuthWindow(Instant now, Object sessionValue) throws IOException {
        if (sessionValue == null) {
            return;
        }
        AuthWindow window = authWindows.remove(String.valueOf(sessionValue));
        if (window != null) {
            writeAuthSuppressionSummary(now, window);
        }
    }

    private void writeCommandSuppressionSummary(Instant now, CommandWindow window) throws IOException {
        if (window.suppressedEvents == 0) {
            return;
        }
        Map<String, Object> summary = new LinkedHashMap<>(window.context);
        summary.put("suppressed", window.suppressedEvents);
        summary.put("limitPerSecond", MAX_COMMAND_EVENTS_PER_SECOND);
        summary.put("windowMillis", EVENT_WINDOW_MILLIS);
        writeEvent(now, "COMMANDS_SUPPRESSED", summary);
    }

    private void writeAuthSuppressionSummary(Instant now, AuthWindow window) throws IOException {
        if (window.suppressedEvents == 0) {
            return;
        }
        Map<String, Object> summary = new LinkedHashMap<>(window.context);
        summary.put("accepted", false);
        summary.put("suppressed", window.suppressedEvents);
        summary.put("distinctUsernames", window.usernames.size());
        summary.put("distinctPasswords", window.passwords.size());
        summary.put("limitPerSecond", MAX_FAILED_AUTH_EVENTS_PER_SECOND);
        summary.put("windowMillis", EVENT_WINDOW_MILLIS);
        writeEvent(now, "AUTH_ATTEMPTS_SUPPRESSED", summary);
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
                Path target = archive(index + 1);
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                hardenPermissions(target);
            }
        }
        if (Files.exists(file)) {
            Path firstArchive = archive(1);
            Files.move(file, firstArchive, StandardCopyOption.REPLACE_EXISTING);
            hardenPermissions(firstArchive);
        }
        writer = openWriter();
        currentBytes = 0;
        hardenPermissions(file);
    }

    private Path archive(int index) {
        return file.resolveSibling(file.getFileName() + "." + index);
    }

    private static void hardenPermissions(Path target) throws IOException {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
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

    private static class EventWindow {
        final Map<String, Object> context = new LinkedHashMap<>();
        long startedAtMillis;
        int loggedEvents;
        long suppressedEvents;

        EventWindow(long startedAtMillis) {
            this.startedAtMillis = startedAtMillis;
        }

        void captureConnectionContext(Map<String, ?> values) {
            copy(values, "sessionId");
            copy(values, "sourceIp");
            copy(values, "sourcePort");
        }

        void reset(long nowMillis) {
            startedAtMillis = nowMillis;
            loggedEvents = 0;
            suppressedEvents = 0;
            context.clear();
        }

        final void copy(Map<String, ?> values, String key) {
            if (values.containsKey(key)) {
                context.put(key, values.get(key));
            }
        }
    }

    private static final class CommandWindow extends EventWindow {
        CommandWindow(long startedAtMillis) {
            super(startedAtMillis);
        }

        void captureContext(Map<String, ?> values) {
            captureConnectionContext(values);
            copy(values, "username");
        }
    }

    private static final class AuthWindow extends EventWindow {
        private final Set<String> usernames = new HashSet<>();
        private final Set<String> passwords = new HashSet<>();

        AuthWindow(long startedAtMillis) {
            super(startedAtMillis);
        }

        void observe(Map<String, ?> values) {
            captureConnectionContext(values);
            addIfPresent(usernames, values.get("username"));
            addIfPresent(passwords, values.get("presentedPassword"));
        }

        @Override
        void reset(long nowMillis) {
            super.reset(nowMillis);
            usernames.clear();
            passwords.clear();
        }

        private static void addIfPresent(Set<String> values, Object value) {
            if (value != null) {
                values.add(String.valueOf(value));
            }
        }
    }
}
