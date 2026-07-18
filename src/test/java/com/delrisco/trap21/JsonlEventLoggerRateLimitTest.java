package com.delrisco.trap21;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class JsonlEventLoggerRateLimitTest {
    private JsonlEventLoggerRateLimitTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("trap21-rate-limit-");
        Path eventLog = temporary.resolve("events.jsonl");
        try (JsonlEventLogger logger = new JsonlEventLogger(eventLog, 1024 * 1024, 2)) {
            for (int index = 0; index < 105; index++) {
                logger.log("COMMAND", Map.of(
                        "sessionId", "rate-limit-session",
                        "sourceIp", "192.0.2.10",
                        "sourcePort", 2121,
                        "username", "ftpuser",
                        "command", "NOOP",
                        "argument", ""));
            }
            logger.log("DISCONNECT", Map.of(
                    "sessionId", "rate-limit-session",
                    "sourceIp", "192.0.2.10",
                    "sourcePort", 2121,
                    "username", "ftpuser",
                    "status", "CLOSED"));
        }

        String events = Files.readString(eventLog, StandardCharsets.UTF_8);
        assertEquals(100, occurrences(events, "\"eventType\":\"COMMAND\","));
        assertContains(events, "\"eventType\":\"COMMANDS_SUPPRESSED\"");
        assertContains(events, "\"suppressed\":5");
        assertContains(events, "\"limitPerSecond\":100");
        assertContains(events, "\"eventType\":\"DISCONNECT\"");

        Files.deleteIfExists(eventLog);
        Files.deleteIfExists(temporary);
        System.out.println("TRAP21 logger rate-limit test passed");
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
}
