package com.delrisco.trap21;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

public final class RuntimeHardeningTest {
    private RuntimeHardeningTest() {
    }

    public static void main(String[] args) throws Exception {
        testDirectoryBudget();
        testRepeatedPassRequiresUser();
        testHiddenWritePathRejected();
        testAbsoluteSessionDeadline();
        testLoggerRecovery();
        System.out.println("TRAP21 runtime hardening tests passed");
    }

    private static void testDirectoryBudget() {
        BoundedLineReader.DirectoryBudget budget = new BoundedLineReader.DirectoryBudget(1, 2);
        assertTrue(budget.reserve(), "Available directory budget was rejected");
        assertTrue(!budget.reserve(), "Directory budget allowed growth beyond its maximum");
    }

    private static void testRepeatedPassRequiresUser() throws Exception {
        try (Socket socket = new Socket()) {
            BoundedLineReader reader = new BoundedLineReader(
                    new StringReader("USER example\r\nPASS first\r\nPASS second\r\n"),
                    4096,
                    socket,
                    5,
                    5,
                    5);
            assertTrue("USER example".equals(reader.readLine()), "USER command changed unexpectedly");
            assertTrue("PASS first".equals(reader.readLine()), "First PASS command was rejected");
            assertThrows(
                    IOException.class,
                    reader::readLine,
                    "Repeated PASS did not terminate the authentication sequence");
        }
    }

    private static void testHiddenWritePathRejected() throws Exception {
        try (Socket socket = new Socket()) {
            BoundedLineReader reader = new BoundedLineReader(
                    new StringReader("STOR /incoming/.secret\r\n"),
                    4096,
                    socket,
                    5,
                    5,
                    5);
            assertThrows(
                    IOException.class,
                    reader::readLine,
                    "Dot-prefixed upload path was accepted");
        }
    }

    private static void testAbsoluteSessionDeadline() throws Exception {
        try (Socket socket = new Socket()) {
            BoundedLineReader reader = new BoundedLineReader(
                    new SlowReader(),
                    4096,
                    socket,
                    5,
                    5,
                    1);
            assertThrows(
                    java.net.SocketTimeoutException.class,
                    reader::readLine,
                    "Absolute session deadline was not enforced");
        }
    }

    private static void testLoggerRecovery() throws Exception {
        Path temporary = Files.createTempDirectory("trap21-logger-health-");
        Path eventLog = temporary.resolve("events.jsonl");
        try (JsonlEventLogger logger = new JsonlEventLogger(eventLog, 4096, 2)) {
            Field writerField = JsonlEventLogger.class.getDeclaredField("writer");
            writerField.setAccessible(true);
            ((BufferedWriter) writerField.get(logger)).close();
            logger.log("FORCED_FAILURE", Map.of("value", 1));

            Files.deleteIfExists(eventLog);
            Files.createDirectory(eventLog);
            assertTrue(!logger.isHealthy(), "Directory-backed event path was reported healthy");
            Files.delete(eventLog);

            assertTrue(logger.isHealthy(), "Telemetry writer did not recover");
            logger.log("RECOVERED", Map.of("value", 2));
            assertTrue(Files.readString(eventLog).contains("\"eventType\":\"RECOVERED\""),
                    "Recovered event was not persisted");
        } finally {
            deleteRecursively(temporary);
        }
    }

    private static void deleteRecursively(Path target) throws Exception {
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void assertThrows(
            Class<? extends Exception> expected,
            ThrowingRunnable operation,
            String message) throws Exception {
        try {
            operation.run();
        } catch (Exception exception) {
            if (expected.isInstance(exception)) {
                return;
            }
            throw exception;
        }
        throw new AssertionError(message);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class SlowReader extends Reader {
        private int position;

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            if (position == 0) {
                buffer[offset] = 'N';
                position++;
                return 1;
            }
            if (position == 1) {
                try {
                    Thread.sleep(1_100L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", exception);
                }
                buffer[offset] = 'O';
                position++;
                return 1;
            }
            return -1;
        }

        @Override
        public void close() {
            // No resources.
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
