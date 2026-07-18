package com.delrisco.trap21;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;

public final class JsonlEventLoggerRateLimitTest {
    private JsonlEventLoggerRateLimitTest() {
    }

    public static void main(String[] args) throws Exception {
        testCommandSuppression();
        testFailedAuthenticationSuppression();
        testWindowRollover();
        System.out.println("TRAP21 logger rate-limit tests passed");
    }

    private static void testCommandSuppression() throws Exception {
        Path temporary = Files.createTempDirectory("trap21-command-rate-limit-");
        Path eventLog = temporary.resolve("events.jsonl");
        MutableClock clock = MutableClock.at("2026-07-18T12:00:00Z");
        try (JsonlEventLogger logger = new JsonlEventLogger(eventLog, 1024 * 1024, 2, clock)) {
            for (int index = 0; index < 105; index++) {
                logger.log("COMMAND", commandEvent("rate-limit-session"));
            }
            logger.log("DISCONNECT", disconnectEvent("rate-limit-session"));
        }

        String events = Files.readString(eventLog, StandardCharsets.UTF_8);
        assertEquals(100, occurrences(events, "\"eventType\":\"COMMAND\","));
        assertContains(events, "\"eventType\":\"COMMANDS_SUPPRESSED\"");
        assertContains(events, "\"suppressed\":5");
        assertContains(events, "\"limitPerSecond\":100");
        assertContains(events, "\"eventType\":\"DISCONNECT\"");
        deleteRecursively(temporary);
    }

    private static void testFailedAuthenticationSuppression() throws Exception {
        Path temporary = Files.createTempDirectory("trap21-auth-rate-limit-");
        Path eventLog = temporary.resolve("events.jsonl");
        MutableClock clock = MutableClock.at("2026-07-18T12:05:00Z");
        try (JsonlEventLogger logger = new JsonlEventLogger(eventLog, 1024 * 1024, 2, clock)) {
            for (int index = 0; index < 30; index++) {
                logger.log("AUTH_ATTEMPT", Map.of(
                        "sessionId", "auth-session",
                        "sourceIp", "192.0.2.20",
                        "sourcePort", 2121,
                        "username", index % 2 == 0 ? "admin" : "ftpuser",
                        "presentedPassword", "wrong-" + index % 3,
                        "accepted", false));
            }
            logger.log("AUTH_ATTEMPT", Map.of(
                    "sessionId", "auth-session",
                    "sourceIp", "192.0.2.20",
                    "sourcePort", 2121,
                    "username", "ftpuser",
                    "presentedPassword", "87654321",
                    "accepted", true,
                    "profile", "TRANSFER"));
            logger.log("DISCONNECT", disconnectEvent("auth-session"));
        }

        String events = Files.readString(eventLog, StandardCharsets.UTF_8);
        assertEquals(26, occurrences(events, "\"eventType\":\"AUTH_ATTEMPT\","));
        assertContains(events, "\"eventType\":\"AUTH_ATTEMPTS_SUPPRESSED\"");
        assertContains(events, "\"suppressed\":5");
        assertContains(events, "\"distinctUsernames\":2");
        assertContains(events, "\"distinctPasswords\":3");
        assertContains(events, "\"limitPerSecond\":25");
        assertContains(events, "\"presentedPassword\":\"87654321\",\"accepted\":true");
        deleteRecursively(temporary);
    }

    private static void testWindowRollover() throws Exception {
        Path temporary = Files.createTempDirectory("trap21-window-rollover-");
        Path eventLog = temporary.resolve("events.jsonl");
        MutableClock clock = MutableClock.at("2026-07-18T12:10:00Z");
        try (JsonlEventLogger logger = new JsonlEventLogger(eventLog, 1024 * 1024, 2, clock)) {
            for (int index = 0; index < 102; index++) {
                logger.log("COMMAND", commandEvent("rollover-session"));
            }
            clock.advance(Duration.ofSeconds(1));
            logger.log("COMMAND", commandEvent("rollover-session"));
            logger.log("DISCONNECT", disconnectEvent("rollover-session"));
        }

        String events = Files.readString(eventLog, StandardCharsets.UTF_8);
        assertEquals(101, occurrences(events, "\"eventType\":\"COMMAND\","));
        assertContains(events, "\"eventType\":\"COMMANDS_SUPPRESSED\"");
        assertContains(events, "\"suppressed\":2");
        deleteRecursively(temporary);
    }

    private static Map<String, Object> commandEvent(String sessionId) {
        return Map.of(
                "sessionId", sessionId,
                "sourceIp", "192.0.2.10",
                "sourcePort", 2121,
                "username", "ftpuser",
                "command", "NOOP",
                "argument", "");
    }

    private static Map<String, Object> disconnectEvent(String sessionId) {
        return Map.of(
                "sessionId", sessionId,
                "sourceIp", "192.0.2.10",
                "sourcePort", 2121,
                "username", "ftpuser",
                "status", "CLOSED");
    }

    private static int occurrences(String value, String expected) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }

    private static void deleteRecursively(Path target) throws Exception {
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void assertContains(String actual, String expected) {
        if (!actual.contains(expected)) {
            throw new AssertionError("Expected <" + actual + "> to contain <" + expected + ">");
        }
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        static MutableClock at(String instant) {
            return new MutableClock(Instant.parse(instant), ZoneOffset.UTC);
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId selectedZone) {
            return new MutableClock(instant, selectedZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
