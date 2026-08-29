package com.delrisco.trap21;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class ClientSession {
    private static final String USERNAME = "ftpuser";
    private static final String PASSWORD = "87654321";
    private static final int MAX_COMMAND_CHARS = 4096;
    private static final DateTimeFormatter LIST_TIME = DateTimeFormatter
            .ofPattern("MMM dd HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final InetAddress bindAddress;
    private final String publicHost;
    private final Socket control;
    private final VirtualFileSystem fileSystem;
    private final JsonlEventLogger logger;
    private final String sessionId = UUID.randomUUID().toString();
    private BufferedReader reader;
    private BufferedWriter writer;
    private ServerSocket passiveListener;
    private String currentDirectory = "/";
    private String pendingUsername;
    private boolean authenticated;

    ClientSession(InetAddress bindAddress, String publicHost, Socket control,
            VirtualFileSystem fileSystem, JsonlEventLogger logger) {
        this.bindAddress = bindAddress;
        this.publicHost = publicHost;
        this.control = control;
        this.fileSystem = fileSystem;
        this.logger = logger;
    }

    void run() throws IOException {
        control.setSoTimeout(Trap21Server.IDLE_TIMEOUT_SECONDS * 1000);
        reader = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8));
        if (!log("CONNECT", Map.of("status", "OPEN"))) {
            throw new IOException("Telemetry is unavailable");
        }
        send(220, "Authorized business use only");
        try {
            String line;
            while ((line = readCommandLine()) != null) {
                if (line.isBlank()) {
                    send(500, "Empty command.");
                    continue;
                }
                Command command = Command.parse(line);
                log("COMMAND", Map.of(
                        "command", command.name,
                        "argument", "PASS".equals(command.name) ? "<redacted>" : command.argumentOrEmpty()));
                if (!dispatch(command)) {
                    return;
                }
            }
        } catch (SocketTimeoutException exception) {
            sendQuietly(421, "Control connection timed out.");
            log("TIMEOUT", Map.of("channel", "control"));
        } finally {
            closePassive();
            log("DISCONNECT", Map.of("status", "CLOSED"));
        }
    }

    private boolean dispatch(Command command) throws IOException {
        return switch (command.name) {
            case "USER" -> user(command.argument);
            case "PASS" -> pass(command.argument);
            case "QUIT" -> quit();
            case "SYST" -> reply(215, "UNIX Type: L8");
            case "NOOP" -> reply(200, "OK");
            case "AUTH" -> reply(502, "TLS is not available.");
            case "PORT", "EPRT" -> reply(502, "Active mode is disabled.");
            default -> authenticated ? authenticated(command) : reply(530, "Please login with USER and PASS.");
        };
    }

    private boolean authenticated(Command command) throws IOException {
        return switch (command.name) {
            case "PWD", "XPWD" -> reply(257, "\"" + currentDirectory + "\"");
            case "CWD", "XCWD" -> cwd(command.argument);
            case "CDUP", "XCUP" -> cwd("..");
            case "TYPE" -> type(command.argument);
            case "PASV" -> passive(false);
            case "EPSV" -> passive(true);
            case "LIST" -> list(command.argument, false);
            case "NLST" -> list(command.argument, true);
            case "RETR" -> retrieve(command.argument);
            case "STOR" -> store(command.argument);
            case "SIZE" -> size(command.argument);
            default -> reply(502, "Command not implemented.");
        };
    }

    private boolean user(String username) throws IOException {
        pendingUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        authenticated = false;
        send(331, "Password required.");
        return true;
    }

    private boolean pass(String password) throws IOException {
        if (pendingUsername == null) {
            return reply(503, "Login with USER first.");
        }
        String presented = password == null ? "" : password;
        authenticated = USERNAME.equals(pendingUsername) && PASSWORD.equals(presented);
        log("AUTH_ATTEMPT", Map.of(
                "presentedUsername", pendingUsername,
                "presentedPassword", presented,
                "accepted", authenticated));
        pendingUsername = null;
        if (!authenticated) {
            return reply(530, "Login incorrect.");
        }
        currentDirectory = "/";
        return reply(230, "User logged in.");
    }

    private boolean quit() throws IOException {
        send(221, "Goodbye.");
        return false;
    }

    private boolean cwd(String argument) throws IOException {
        try {
            String target = fileSystem.resolve(currentDirectory, argument);
            if (!fileSystem.isDirectory(target)) {
                return reply(550, "Directory unavailable.");
            }
            currentDirectory = target;
            return reply(250, "Directory changed.");
        } catch (IOException exception) {
            return reply(550, "Directory unavailable.");
        }
    }

    private boolean type(String argument) throws IOException {
        String value = argument == null ? "" : argument.trim().toUpperCase(Locale.ROOT);
        if (value.equals("I") || value.equals("A") || value.equals("L 8") || value.equals("L8")) {
            return reply(200, "Type accepted.");
        }
        return reply(504, "Unsupported TYPE.");
    }

    private boolean passive(boolean extended) throws IOException {
        closePassive();
        passiveListener = bindPassiveListener();
        int port = passiveListener.getLocalPort();
        if (extended) {
            send(229, "Entering Extended Passive Mode (|||" + port + "|).");
        } else {
            InetAddress address = InetAddress.getByName(publicHost);
            if (!(address instanceof Inet4Address) || address.isAnyLocalAddress()) {
                closePassive();
                return reply(425, "PASV requires a reachable IPv4 TRAP21_PUBLIC_HOST.");
            }
            byte[] ip = address.getAddress();
            send(227, String.format(Locale.ROOT, "Entering Passive Mode (%d,%d,%d,%d,%d,%d).",
                    ip[0] & 255, ip[1] & 255, ip[2] & 255, ip[3] & 255, port / 256, port % 256));
        }
        return true;
    }

    private ServerSocket bindPassiveListener() throws IOException {
        IOException last = null;
        for (int port = Trap21Server.PASSIVE_START; port <= Trap21Server.PASSIVE_END; port++) {
            ServerSocket candidate = new ServerSocket();
            try {
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress(bindAddress, port));
                candidate.setSoTimeout(Trap21Server.DATA_TIMEOUT_SECONDS * 1000);
                return candidate;
            } catch (IOException exception) {
                last = exception;
                candidate.close();
            }
        }
        throw new IOException("No passive ports are available", last);
    }

    private boolean list(String argument, boolean namesOnly) throws IOException {
        try {
            String target = fileSystem.resolve(currentDirectory, listPath(argument));
            List<VirtualFileSystem.Entry> entries = fileSystem.list(target);
            try (Socket data = openDataConnection("Opening data connection for file list.");
                    OutputStream output = data == null ? OutputStream.nullOutputStream() : data.getOutputStream()) {
                if (data == null) {
                    return true;
                }
                for (VirtualFileSystem.Entry entry : entries) {
                    String line = namesOnly ? entry.name() + "\r\n" : listingLine(entry);
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                }
                output.flush();
            }
            send(226, "Transfer complete.");
            log("LIST", Map.of("path", target, "entries", entries.size()));
            return true;
        } catch (IOException exception) {
            if (passiveListener != null) {
                closePassive();
            }
            return reply(550, "Requested action not taken.");
        }
    }

    private boolean retrieve(String argument) throws IOException {
        try {
            String target = fileSystem.resolve(currentDirectory, argument);
            byte[] content = fileSystem.read(target);
            try (Socket data = openDataConnection("Opening data connection.");
                    ByteArrayInputStream input = new ByteArrayInputStream(content);
                    OutputStream output = data == null ? OutputStream.nullOutputStream() : data.getOutputStream()) {
                if (data == null) {
                    return true;
                }
                input.transferTo(output);
                output.flush();
            }
            send(226, "Transfer complete.");
            log("DOWNLOAD", Map.of("path", target, "bytes", content.length));
            return true;
        } catch (IOException exception) {
            return reply(550, "File unavailable.");
        }
    }

    private boolean store(String argument) throws IOException {
        String target;
        try {
            target = fileSystem.resolve(currentDirectory, argument);
            if (!fileSystem.canStore(target)) {
                return reply(550, "Uploads are only allowed in /incoming.");
            }
        } catch (IOException exception) {
            return reply(550, "Invalid target path.");
        }
        try (Socket data = openDataConnection("Opening data connection for upload.")) {
            if (data == null) {
                return true;
            }
            VirtualFileSystem.Capture capture = fileSystem.capture(sessionId, target, data.getInputStream());
            send(226, "Transfer complete.");
            log("UPLOAD", Map.of(
                    "path", target,
                    "bytes", capture.size(),
                    "sha256", capture.sha256(),
                    "quarantineFile", capture.file().toString(),
                    "status", "CAPTURED"));
        } catch (VirtualFileSystem.UploadTooLargeException exception) {
            send(552, "Upload exceeds the file limit.");
        } catch (VirtualFileSystem.QuarantineFullException exception) {
            send(452, "Quarantine capacity exceeded.");
        } catch (IOException exception) {
            send(426, "Transfer aborted.");
            log("UPLOAD", Map.of("path", target, "status", "FAILED"));
        }
        return true;
    }

    private boolean size(String argument) throws IOException {
        try {
            String target = fileSystem.resolve(currentDirectory, argument);
            return reply(213, String.valueOf(fileSystem.size(target)));
        } catch (IOException exception) {
            return reply(550, "File unavailable.");
        }
    }

    private Socket openDataConnection(String message) throws IOException {
        ServerSocket listener = passiveListener;
        passiveListener = null;
        if (listener == null || listener.isClosed()) {
            send(425, "Use PASV or EPSV first.");
            return null;
        }
        send(150, message);
        try (listener) {
            Socket data = listener.accept();
            data.setSoTimeout(Trap21Server.DATA_TIMEOUT_SECONDS * 1000);
            if (!data.getInetAddress().equals(control.getInetAddress())) {
                String source = data.getInetAddress().getHostAddress();
                data.close();
                send(425, "Data source does not match control connection.");
                log("DATA_REJECTED", Map.of("sourceIp", source));
                return null;
            }
            return data;
        } catch (SocketTimeoutException exception) {
            send(425, "Data connection timed out.");
            return null;
        }
    }

    private String readCommandLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = reader.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                if (line.length() >= MAX_COMMAND_CHARS) {
                    throw new IOException("FTP command exceeds limit");
                }
                line.append((char) value);
            }
        }
        return value == -1 && line.isEmpty() ? null : line.toString();
    }

    private String listingLine(VirtualFileSystem.Entry entry) {
        return String.format(Locale.ENGLISH, "%s 1 ftp operations %10d %s %s\r\n",
                entry.directory() ? "drwxr-xr-x" : "-rw-r--r--",
                entry.size(), LIST_TIME.format(entry.modified()), entry.name());
    }

    private String listPath(String argument) {
        if (argument == null || argument.isBlank()) {
            return currentDirectory;
        }
        String[] tokens = argument.trim().split("\\s+");
        for (int i = tokens.length - 1; i >= 0; i--) {
            if (!tokens[i].startsWith("-")) {
                return tokens[i];
            }
        }
        return currentDirectory;
    }

    private void closePassive() {
        ServerSocket listener = passiveListener;
        passiveListener = null;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
                // Best-effort cleanup.
            }
        }
    }

    private boolean reply(int code, String message) throws IOException {
        send(code, message);
        return true;
    }

    private void send(int code, String message) throws IOException {
        writer.write(code + " " + message + "\r\n");
        writer.flush();
    }

    private void sendQuietly(int code, String message) {
        try {
            send(code, message);
        } catch (IOException ignored) {
            // Connection may already be closed.
        }
    }

    private boolean log(String type, Map<String, ?> values) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sessionId", sessionId);
        event.put("sourceIp", control.getInetAddress().getHostAddress());
        event.put("sourcePort", control.getPort());
        event.putAll(values);
        return logger.log(type, event);
    }

    private record Command(String name, String argument) {
        static Command parse(String line) {
            int space = line.indexOf(' ');
            String name = (space < 0 ? line : line.substring(0, space)).trim().toUpperCase(Locale.ROOT);
            String argument = space < 0 ? null : line.substring(space + 1).trim();
            return new Command(name, argument);
        }

        String argumentOrEmpty() {
            return argument == null ? "" : argument;
        }
    }
}
