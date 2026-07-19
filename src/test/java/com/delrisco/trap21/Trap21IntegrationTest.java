package com.delrisco.trap21;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.FileSystemException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class Trap21IntegrationTest {
    private static final Pattern EPSV_PORT = Pattern.compile("\\(\\|\\|\\|(\\d+)\\|\\)");
    private static final String INITIAL_UPLOAD = "trap21 upload probe\n";
    private static final String APPENDED_UPLOAD = "appended content\n";
    private static final String COMPLETE_UPLOAD = INITIAL_UPLOAD + APPENDED_UPLOAD;
    private static final String ASCII_LOCAL = "line one\nline two\n";
    private static final String ASCII_NETWORK = "line one\r\nline two\r\n";

    private Trap21IntegrationTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("trap21-integration-");
        Path outside = Files.createTempDirectory("trap21-outside-");
        Files.writeString(outside.resolve("secret.txt"), "outside virtual root", StandardCharsets.UTF_8);
        Files.createDirectories(temporary.resolve("vfs/archive"));
        Files.writeString(temporary.resolve("vfs/archive/.operator-note"), "hidden", StandardCharsets.UTF_8);
        Trap21Config config = new Trap21Config(
                InetAddress.getByName("127.0.0.1"),
                0,
                0,
                0,
                "127.0.0.1",
                temporary,
                10,
                3,
                5,
                30,
                1024 * 1024,
                16L * 1024 * 1024,
                100,
                30,
                100,
                200,
                1024 * 1024,
                3,
                8,
                4);

        try (Trap21Server server = new Trap21Server(config)) {
            server.start();
            testDefaultCredentialsAndTransfers(server.port());
            testInvalidCredentials(server.port());
            testAuthenticationStateReset(server.port());
            testListOptionParsing(server.port());
            testAnonymousIsolation(server.port());
            testSymbolicLinkIsolation(server.port(), temporary, outside);
            testCapturedDirectoryRename(server.port());
            testCommandDeadline(server.port());
            testPerSourceSessionLimit(server.port());
        }

        assertTrue(Files.exists(temporary.resolve("events.jsonl")), "events.jsonl was not created");
        String events = Files.readString(temporary.resolve("events.jsonl"), StandardCharsets.UTF_8);
        assertContains(events, "\"eventType\":\"AUTH_ATTEMPT\"");
        assertContains(events, "\"presentedPassword\":\"87654321\"");
        assertContains(events, "\"eventType\":\"UPLOAD\"");
        assertContains(events, "\"status\":\"CAPTURED\"");
        assertContains(events, "\"sha256\":\"" + sha256(INITIAL_UPLOAD) + "\"");
        assertContains(events, "\"sha256\":\"" + sha256(COMPLETE_UPLOAD) + "\"");
        assertContains(events, "\"sha256\":\"" + sha256(ASCII_LOCAL) + "\"");

        List<String> capturedContents;
        try (Stream<Path> paths = Files.walk(temporary.resolve("quarantine"))) {
            capturedContents = paths
                    .filter(Files::isRegularFile)
                    .map(Trap21IntegrationTest::readStringUnchecked)
                    .toList();
        }
        assertTrue(capturedContents.contains(INITIAL_UPLOAD), "Initial upload was not captured in quarantine");
        assertTrue(capturedContents.contains(COMPLETE_UPLOAD), "Appended upload was not captured in quarantine");
        assertTrue(capturedContents.contains(ASCII_LOCAL), "ASCII upload was not normalized in quarantine");

        assertEquals("/Windows/System32", VirtualFileSystem.normalizeVirtualPath("../../../../Windows/System32"));
        assertTrue(!Files.exists(temporary.resolve("Windows")), "Traversal created a path outside the virtual root");

        testStorageQuotaAndRetention();
        testFailedCaptureCleanup();
        testVfsEntryBudgets();
        testSessionWatchdog();
        testEventLogRotation();

        deleteRecursively(temporary);
        deleteRecursively(outside);
        System.out.println("TRAP21 integration tests passed");
    }

    private static void testDefaultCredentialsAndTransfers(int port) throws Exception {
        try (FtpClient client = new FtpClient(port)) {
            assertEquals("220 Authorized business use only", client.readReply());
            client.expect("USER ftpuser", 331);
            client.expect("PASS 87654321", 230);
            client.expect("PWD", 257);
            client.expect("CWD /backups", 550);
            client.expect("CWD /archive/completed", 250);
            client.expect("CWD /", 250);

            String rootListing = client.downloadData("LIST");
            assertContains(rootListing, "incoming");
            assertContains(rootListing, "outgoing");
            assertContains(rootListing, "pub");
            assertNotContains(rootListing, "backups");
            assertNotContains(rootListing, "config");

            String readme = client.downloadData("RETR /pub/README.txt");
            assertContains(readme, "Managed File Transfer Gateway");

            client.uploadData("STOR /incoming/probe.txt", INITIAL_UPLOAD);
            client.uploadData("APPE /incoming/probe.txt", APPENDED_UPLOAD);
            String incoming = client.downloadData("LIST /incoming");
            assertContains(incoming, "probe.txt");
            assertEquals(COMPLETE_UPLOAD, client.downloadData("RETR /incoming/probe.txt"));

            client.expect("TYPE A", 200);
            client.uploadData("STOR /incoming/ascii.txt", ASCII_NETWORK);
            client.expect("TYPE I", 200);
            assertEquals(ASCII_LOCAL, client.downloadData("RETR /incoming/ascii.txt"));
            client.expect("TYPE A", 200);
            assertEquals(ASCII_NETWORK, client.downloadData("RETR /incoming/ascii.txt"));
            client.expect("TYPE I", 200);

            client.abortUpload("STOR /incoming/aborted.txt", "partial upload");
            assertNotContains(client.downloadData("NLST /incoming"), "aborted.txt");
            client.expect("QUIT", 221);
        }
    }

    private static void testInvalidCredentials(int port) throws Exception {
        try (FtpClient client = new FtpClient(port)) {
            client.expectReply(220);
            client.expect("USER admin", 331);
            client.expect("PASS incorrect", 530);
            client.expect("PWD", 530);
            client.expect("QUIT", 221);
        }
    }


    private static void testAuthenticationStateReset(int port) throws Exception {
        try (FtpClient client = new FtpClient(port)) {
            client.expectReply(220);
            client.expect("USER admin", 331);
            client.expect("PASS admin123", 230);
            client.expect("USER", 501);
            client.expect("PASS incorrect", 503);
            client.expect("PWD", 530);
            client.expect("USER admin", 331);
            client.expect("PASS incorrect", 530);
            client.expect("PWD", 530);
            client.expect("PASS admin123", 503);
            client.expect("QUIT", 221);
        }
    }

    private static void testListOptionParsing(int port) throws Exception {
        try (FtpClient client = new FtpClient(port)) {
            client.expectReply(220);
            client.expect("USER admin", 331);
            client.expect("PASS admin123", 230);
            assertNotContains(client.downloadData("LIST /archive"), ".operator-note");
            assertContains(client.downloadData("LIST -a /archive"), ".operator-note");
            client.expect("QUIT", 221);
        }
    }

    private static void testAnonymousIsolation(int port) throws Exception {
        try (FtpClient client = new FtpClient(port)) {
            client.expectReply(220);
            client.expect("USER anonymous", 331);
            client.expect("PASS researcher@example.invalid", 230);
            String root = client.downloadData("NLST /");
            assertContains(root, "pub");
            assertNotContains(root, "incoming");
            client.expect("CWD /backups", 550);
            client.expect("STOR /pub/not-allowed.txt", 550);
            client.expect("QUIT", 221);
        }
    }

    private static void testSymbolicLinkIsolation(int port, Path dataDirectory, Path outside) throws Exception {
        Path link = dataDirectory.resolve("vfs/incoming/escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            System.out.println("Symbolic-link isolation test skipped: " + exception.getMessage());
            return;
        }

        try (FtpClient client = new FtpClient(port)) {
            client.expectReply(220);
            client.expect("USER ftpuser", 331);
            client.expect("PASS 87654321", 230);
            client.expect("CWD /incoming/escape", 550);
            client.expect("RETR /incoming/escape/secret.txt", 550);
            client.expect("STOR /incoming/escape/created.txt", 550);
            client.expect("MKD /incoming/escape/created", 550);
            client.expect("QUIT", 221);
        }

        assertTrue(!Files.exists(outside.resolve("created.txt")), "Upload escaped through a symbolic link");
        assertTrue(!Files.exists(outside.resolve("created")), "Directory creation escaped through a symbolic link");
    }

    private static void testCapturedDirectoryRename(int port) throws Exception {
        try (FtpClient client = new FtpClient(port)) {
            client.expectReply(220);
            client.expect("USER admin", 331);
            client.expect("PASS admin123", 230);
            client.expect("MKD /users/admin/rename-source", 257);
            client.uploadData("STOR /users/admin/rename-source/probe.txt", INITIAL_UPLOAD);
            client.expect("RNFR /users/admin/rename-source", 350);
            client.expect("RNTO /users/admin/rename-target", 250);
            assertEquals(INITIAL_UPLOAD, client.downloadData("RETR /users/admin/rename-target/probe.txt"));
            client.expect("QUIT", 221);
        }
    }

    private static void testCommandDeadline(int port) throws Exception {
        try (FtpClient client = new FtpClient(port)) {
            client.expectReply(220);
            client.sendPartial("NO");
            client.expectReply(421);
        }
    }

    private static void testPerSourceSessionLimit(int port) throws Exception {
        Thread.sleep(200);
        List<FtpClient> clients = new ArrayList<>();
        try {
            for (int index = 0; index < 4; index++) {
                FtpClient client = new FtpClient(port);
                client.expectReply(220);
                clients.add(client);
            }
            try (FtpClient rejected = new FtpClient(port)) {
                rejected.expectReply(421);
            }
        } finally {
            for (FtpClient client : clients) {
                client.close();
            }
        }
    }

    private static void testStorageQuotaAndRetention() throws Exception {
        Path temporary = Files.createTempDirectory("trap21-storage-");
        Path expired = temporary.resolve("quarantine/expired/old.bin");
        Files.createDirectories(expired.getParent());
        Files.writeString(expired, "expired", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(expired, FileTime.from(Instant.now().minus(Duration.ofDays(31))));

        VirtualFileSystem fileSystem = new VirtualFileSystem(temporary, 64, 2, 30, 100, 200);
        assertTrue(!Files.exists(expired), "Expired quarantine artifact was not pruned");
        byte[] content = "x".repeat(32).getBytes(StandardCharsets.UTF_8);
        fileSystem.capture("quota-a", "/incoming/a.bin", new ByteArrayInputStream(content), 32, false);
        fileSystem.capture("quota-b", "/incoming/b.bin", new ByteArrayInputStream(content), 32, false);
        boolean rejected = false;
        try {
            fileSystem.capture("quota-c", "/incoming/c.bin", new ByteArrayInputStream(content), 32, false);
        } catch (VirtualFileSystem.StorageQuotaExceededException expected) {
            rejected = true;
        }
        assertTrue(rejected, "Quarantine quota did not reject an additional upload");
        deleteRecursively(temporary);
    }

    private static void testFailedCaptureCleanup() throws Exception {
        Path temporary = Files.createTempDirectory("trap21-failed-capture-");
        try {
            VirtualFileSystem fileSystem = new VirtualFileSystem(temporary, 1024, 10, 30, 100, 200);
            InputStream failingInput = new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("simulated interrupted upload");
                }
            };

            assertThrows(IOException.class,
                    () -> fileSystem.capture("empty-session", "/incoming/failed.bin", failingInput, 1024, false),
                    "Interrupted upload did not fail");
            assertTrue(!Files.exists(temporary.resolve("quarantine/empty-session")),
                    "Interrupted upload left an empty quarantine session directory");

            fileSystem.capture("retained-session", "/incoming/complete.bin",
                    new ByteArrayInputStream("complete".getBytes(StandardCharsets.UTF_8)), 1024, false);
            assertThrows(IOException.class,
                    () -> fileSystem.capture("retained-session", "/incoming/failed-again.bin",
                            new InputStream() {
                                @Override
                                public int read() throws IOException {
                                    throw new IOException("simulated interrupted upload");
                                }
                            },
                            1024,
                            false),
                    "Second interrupted upload did not fail");
            try (Stream<Path> paths = Files.list(temporary.resolve("quarantine/retained-session"))) {
                assertTrue(paths.filter(Files::isRegularFile).count() == 1L,
                        "Cleanup removed an earlier capture or retained the failed partial file");
            }
        } finally {
            deleteRecursively(temporary);
        }
    }

    private static void testVfsEntryBudgets() throws Exception {
        Path temporary = Files.createTempDirectory("trap21-vfs-budget-");
        try {
            VirtualFileSystem seed = new VirtualFileSystem(temporary, 1024 * 1024, 100, 30, 100, 100);
            int existingDirectories;
            try (Stream<Path> paths = Files.walk(temporary.resolve("vfs"))) {
                existingDirectories = (int) paths.filter(Files::isDirectory).count() - 1;
            }
            VirtualFileSystem initial = new VirtualFileSystem(
                    temporary, 1024 * 1024, 100, 30, existingDirectories + 1, 100);
            initial.makeDirectory("/incoming/one");
            assertThrows(VirtualFileSystem.VfsEntryLimitExceededException.class,
                    () -> initial.makeDirectory("/incoming/two"),
                    "Directory budget allowed growth beyond its maximum");
            initial.removeDirectory("/incoming/one");
            initial.makeDirectory("/incoming/two");

            int existingFiles;
            try (Stream<Path> paths = Files.walk(temporary.resolve("vfs"))) {
                existingFiles = (int) paths.filter(Files::isRegularFile).count();
            }
            VirtualFileSystem firstRun = new VirtualFileSystem(
                    temporary, 1024 * 1024, 100, 30, 100, existingFiles + 1);
            firstRun.capture("first", "/incoming/first.bin",
                    new ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8)), 1024, false);
            VirtualFileSystem secondRun = new VirtualFileSystem(
                    temporary, 1024 * 1024, 100, 30, 100, existingFiles + 1);
            assertThrows(VirtualFileSystem.VfsEntryLimitExceededException.class,
                    () -> secondRun.capture("second", "/incoming/second.bin",
                            new ByteArrayInputStream("second".getBytes(StandardCharsets.UTF_8)), 1024, false),
                    "Persisted VFS placeholders bypassed the file budget after restart");
        } finally {
            deleteRecursively(temporary);
        }
    }

    private static void testSessionWatchdog() throws Exception {
        Path temporary = Files.createTempDirectory("trap21-session-watchdog-");
        Trap21Config config = new Trap21Config(
                InetAddress.getByName("127.0.0.1"), 0, 0, 0, "127.0.0.1", temporary,
                30, 5, 5, 1, 1024, 1024 * 1024, 10, 30, 100, 100,
                1024 * 1024, 2, 4, 4);
        try (Trap21Server server = new Trap21Server(config); FtpClient client = new FtpClientAfterStart(server)) {
            client.expectReply(220);
            Thread.sleep(1_300L);
            boolean closed = false;
            try {
                closed = client.readReplyOrNull() == null;
            } catch (IOException expected) {
                closed = true;
            }
            assertTrue(closed, "Absolute session watchdog did not close the control socket");
        } finally {
            deleteRecursively(temporary);
        }
    }

    private static void testEventLogRotation() throws Exception {
        Path temporary = Files.createTempDirectory("trap21-logging-");
        Path eventLog = temporary.resolve("events.jsonl");
        try (JsonlEventLogger logger = new JsonlEventLogger(eventLog, 256, 2)) {
            for (int index = 0; index < 20; index++) {
                logger.log("ROTATION_TEST", Map.of("index", index, "payload", "x".repeat(80)));
            }
        }
        assertTrue(Files.exists(temporary.resolve("events.jsonl.1")), "Event log was not rotated");
        assertTrue(!Files.exists(temporary.resolve("events.jsonl.3")), "Too many event archives were retained");
        deleteRecursively(temporary);
    }

    private static void deleteRecursively(Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String readStringUnchecked(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read captured upload " + path, exception);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void assertContains(String actual, String expected) {
        if (!actual.contains(expected)) {
            throw new AssertionError("Expected <" + actual + "> to contain <" + expected + ">");
        }
    }

    private static void assertNotContains(String actual, String unexpected) {
        if (actual.contains(unexpected)) {
            throw new AssertionError("Expected <" + actual + "> not to contain <" + unexpected + ">");
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static class FtpClient implements AutoCloseable {
        private final Socket control;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private final int port;

        FtpClient(int port) throws IOException {
            this.port = port;
            this.control = new Socket("127.0.0.1", port);
            this.control.setSoTimeout(5_000);
            this.reader = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8));
        }

        String readReply() throws IOException {
            String line = readReplyOrNull();
            if (line == null) {
                throw new IOException("FTP server closed the control connection");
            }
            return line;
        }

        String readReplyOrNull() throws IOException {
            return reader.readLine();
        }

        void expectReply(int code) throws IOException {
            String reply = readReply();
            if (!reply.startsWith(code + " ") && !reply.startsWith(code + "-")) {
                throw new AssertionError("Expected FTP " + code + " but received: " + reply);
            }
        }

        void expect(String command, int code) throws IOException {
            send(command);
            expectReply(code);
        }

        String downloadData(String command) throws IOException {
            int dataPort = enterExtendedPassiveMode();
            try (Socket data = new Socket("127.0.0.1", dataPort)) {
                data.setSoTimeout(5_000);
                send(command);
                expectReply(150);
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                data.getInputStream().transferTo(received);
                expectReply(226);
                return received.toString(StandardCharsets.UTF_8);
            }
        }

        void uploadData(String command, String content) throws IOException {
            int dataPort = enterExtendedPassiveMode();
            try (Socket data = new Socket("127.0.0.1", dataPort)) {
                data.setSoTimeout(5_000);
                send(command);
                expectReply(150);
                data.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
                data.shutdownOutput();
                expectReply(226);
            }
        }

        void abortUpload(String command, String partialContent) throws IOException {
            int dataPort = enterExtendedPassiveMode();
            try (Socket data = new Socket("127.0.0.1", dataPort)) {
                data.setSoTimeout(5_000);
                send(command);
                expectReply(150);
                data.getOutputStream().write(partialContent.getBytes(StandardCharsets.UTF_8));
                data.getOutputStream().flush();
                send("ABOR");
                expectReply(426);
                expectReply(226);
            }
        }

        private int enterExtendedPassiveMode() throws IOException {
            send("EPSV");
            String response = readReply();
            if (!response.startsWith("229 ")) {
                throw new AssertionError("Expected EPSV response but received: " + response);
            }
            Matcher matcher = EPSV_PORT.matcher(response);
            if (!matcher.find()) {
                throw new AssertionError("Could not parse EPSV port from: " + response);
            }
            return Integer.parseInt(matcher.group(1));
        }

        private void send(String command) throws IOException {
            writer.write(command);
            writer.write("\r\n");
            writer.flush();
        }

        private void sendPartial(String commandFragment) throws IOException {
            writer.write(commandFragment);
            writer.flush();
        }

        @Override
        public void close() throws IOException {
            control.close();
        }
    }

    private static final class FtpClientAfterStart extends FtpClient {
        FtpClientAfterStart(Trap21Server server) throws IOException {
            super(startAndGetPort(server));
        }

        private static int startAndGetPort(Trap21Server server) throws IOException {
            server.start();
            return server.port();
        }
    }
}
