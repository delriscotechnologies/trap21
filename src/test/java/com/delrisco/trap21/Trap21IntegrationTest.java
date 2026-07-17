package com.delrisco.trap21;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class Trap21IntegrationTest {
    private static final Pattern EPSV_PORT = Pattern.compile("\\(\\|\\|\\|(\\d+)\\|\\)");

    private Trap21IntegrationTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("trap21-integration-");
        Trap21Config config = new Trap21Config(
                InetAddress.getByName("127.0.0.1"),
                0,
                0,
                0,
                "127.0.0.1",
                temporary,
                10,
                5,
                1024 * 1024,
                8);

        try (Trap21Server server = new Trap21Server(config)) {
            server.start();
            testDefaultCredentialsAndTransfers(server.port());
            testInvalidCredentials(server.port());
            testAnonymousIsolation(server.port());
        }

        assertTrue(Files.exists(temporary.resolve("events.jsonl")), "events.jsonl was not created");
        String events = Files.readString(temporary.resolve("events.jsonl"), StandardCharsets.UTF_8);
        assertContains(events, "\"eventType\":\"AUTH_ATTEMPT\"");
        assertContains(events, "\"presentedPassword\":\"87654321\"");
        assertContains(events, "\"eventType\":\"UPLOAD\"");
        assertContains(events, "\"status\":\"CAPTURED\"");

        Path captured;
        try (Stream<Path> paths = Files.walk(temporary.resolve("quarantine"))) {
            captured = paths.filter(Files::isRegularFile).findFirst().orElseThrow(
                    () -> new AssertionError("Uploaded file was not captured in quarantine"));
        }
        assertEquals("trap21 upload probe\n", Files.readString(captured, StandardCharsets.UTF_8));

        assertEquals("/Windows/System32", VirtualFileSystem.normalizeVirtualPath("../../../../Windows/System32"));
        assertTrue(!Files.exists(temporary.resolve("Windows")), "Traversal created a path outside the virtual root");

        deleteRecursively(temporary);
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

            client.uploadData("STOR /incoming/probe.txt", "trap21 upload probe\n");
            String incoming = client.downloadData("LIST /incoming");
            assertContains(incoming, "probe.txt");
            assertContains(client.downloadData("RETR /incoming/probe.txt"), "trap21 upload probe");
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

    private static void deleteRecursively(Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
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

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FtpClient implements AutoCloseable {
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
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("FTP server closed the control connection");
            }
            return line;
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

        @Override
        public void close() throws IOException {
            control.close();
        }
    }
}
