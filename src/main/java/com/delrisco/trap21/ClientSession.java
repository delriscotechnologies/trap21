package com.delrisco.trap21;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class ClientSession {
    private static final String USER = "ftpuser", PASS = "87654321";
    private static final int MAX_COMMAND = 4096, MAX_SESSION_SECONDS = 300;
    private static final Map<String, byte[]> FILES = Map.of(
            "/pub/README.txt", bytes("Managed File Transfer Gateway\r\nUse assigned credentials for partner transfers.\r\n"),
            "/pub/partners.txt", bytes("northwind\r\ncontoso\r\nfabrikam\r\n"),
            "/incoming/INSTRUCTIONS.txt", bytes("Upload partner files to this directory.\r\n"));
    private static final Set<String> DIRS = Set.of("/", "/pub", "/incoming");

    private final InetAddress bind;
    private final String publicHost;
    private final Socket control;
    private final Evidence evidence;
    private final String id = UUID.randomUUID().toString();
    private BufferedReader in;
    private BufferedWriter out;
    private ServerSocket passive;
    private String cwd = "/", username;
    private boolean authenticated;

    ClientSession(InetAddress bind, String publicHost, Socket control, Evidence evidence) {
        this.bind = bind; this.publicHost = publicHost; this.control = control; this.evidence = evidence;
    }

    void run() throws IOException {
        control.setSoTimeout(Trap21Application.IDLE_SECONDS * 1000);
        in = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8));
        log("CONNECT"); send(220, "Authorized business use only");
        long deadline = System.nanoTime() + MAX_SESSION_SECONDS * 1_000_000_000L;
        try {
            for (String line; (line = readLine()) != null;) {
                if (System.nanoTime() > deadline) { send(421, "Session lifetime exceeded."); return; }
                if (line.isBlank()) { send(500, "Empty command."); continue; }
                int p = line.indexOf(' ');
                String cmd = (p < 0 ? line : line.substring(0, p)).toUpperCase(Locale.ROOT);
                String arg = p < 0 ? "" : line.substring(p + 1).trim();
                evidence.log("COMMAND", "sessionId", id, "sourceIp", ip(), "command", cmd,
                        "argument", cmd.equals("PASS") ? "<redacted>" : arg);
                if (!dispatch(cmd, arg)) return;
            }
        } catch (SocketTimeoutException e) {
            try { send(421, "Control connection timed out."); } catch (IOException ignored) {}
            log("TIMEOUT");
        } finally {
            closePassive(); log("DISCONNECT");
        }
    }

    private boolean dispatch(String cmd, String arg) throws IOException {
        switch (cmd) {
            case "USER" -> { username = arg.toLowerCase(Locale.ROOT); authenticated = false; send(331, "Password required."); }
            case "PASS" -> {
                if (username == null) { send(503, "Login with USER first."); break; }
                authenticated = USER.equals(username) && PASS.equals(arg);
                evidence.log("AUTH_ATTEMPT", "sessionId", id, "sourceIp", ip(), "username", String.valueOf(username),
                        "password", arg, "accepted", authenticated);
                username = null; send(authenticated ? 230 : 530, authenticated ? "User logged in." : "Login incorrect.");
            }
            case "QUIT" -> { send(221, "Goodbye."); return false; }
            case "SYST" -> send(215, "UNIX Type: L8");
            case "NOOP" -> send(200, "OK");
            case "AUTH", "PORT", "EPRT" -> send(502, "Command not implemented.");
            default -> {
                if (!authenticated) send(530, "Please login with USER and PASS.");
                else command(cmd, arg);
            }
        }
        return true;
    }

    private void command(String cmd, String arg) throws IOException {
        try { switch (cmd) {
            case "PWD" -> send(257, "\"" + cwd + "\"");
            case "CWD" -> cwd(arg);
            case "CDUP" -> cwd("..");
            case "TYPE" -> { boolean ok = arg.equalsIgnoreCase("I") || arg.equalsIgnoreCase("A"); send(ok ? 200 : 504, ok ? "Type accepted." : "Unsupported TYPE."); }
            case "PASV" -> passive(false);
            case "EPSV" -> passive(true);
            case "LIST" -> list(arg);
            case "RETR" -> retr(arg);
            case "STOR" -> stor(arg);
            default -> send(502, "Command not implemented.");
        } } catch (IllegalArgumentException e) { send(550, "Invalid path."); }
    }

    private void cwd(String arg) throws IOException {
        String path = path(arg);
        if (DIRS.contains(path)) { cwd = path; send(250, "Directory changed."); }
        else send(550, "Directory unavailable.");
    }

    private void passive(boolean epsv) throws IOException {
        closePassive();
        for (int port = Trap21Application.PASSIVE_START; port <= Trap21Application.PASSIVE_END; port++) {
            try {
                passive = new ServerSocket(); passive.setReuseAddress(true);
                passive.bind(new InetSocketAddress(bind, port));
                passive.setSoTimeout(Trap21Application.DATA_SECONDS * 1000);
                if (epsv) send(229, "Entering Extended Passive Mode (|||" + port + "|).");
                else {
                    InetAddress a = InetAddress.getByName(publicHost);
                    if (!(a instanceof Inet4Address) || a.isAnyLocalAddress()) throw new IOException("Invalid PASV host");
                    byte[] ip = a.getAddress();
                    send(227, String.format(Locale.ROOT, "Entering Passive Mode (%d,%d,%d,%d,%d,%d).",
                            ip[0]&255, ip[1]&255, ip[2]&255, ip[3]&255, port/256, port%256));
                }
                return;
            } catch (IOException e) { closePassive(); }
        }
        send(425, "No passive ports available.");
    }

    private void list(String arg) throws IOException {
        String target = arg.isBlank() || arg.startsWith("-") ? cwd : path(arg);
        String body = listing(target);
        if (body == null) { send(550, "Path unavailable."); return; }
        try (Socket data = data("Opening data connection for file list.")) {
            if (data == null) return;
            data.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        send(226, "Transfer complete.");
        evidence.log("LIST", "sessionId", id, "sourceIp", ip(), "path", target);
    }

    private void retr(String arg) throws IOException {
        String target = path(arg); byte[] body = FILES.get(target);
        if (body == null) { send(550, "File unavailable."); return; }
        try (Socket data = data("Opening data connection.")) {
            if (data == null) return;
            data.getOutputStream().write(body);
        }
        send(226, "Transfer complete.");
        evidence.log("DOWNLOAD", "sessionId", id, "sourceIp", ip(), "path", target, "bytes", body.length);
    }

    private void stor(String arg) throws IOException {
        String target = path(arg);
        if (!target.startsWith("/incoming/") || target.equals("/incoming/")) { send(550, "Uploads are only allowed in /incoming."); return; }
        try (Socket data = data("Opening data connection for upload.")) {
            if (data == null) return;
            Evidence.Capture c = evidence.capture(id, target.substring(target.lastIndexOf('/') + 1), data.getInputStream());
            send(226, "Transfer complete.");
            evidence.log("UPLOAD", "sessionId", id, "sourceIp", ip(), "path", target, "bytes", c.size(),
                    "sha256", c.sha256(), "quarantineFile", c.file());
        } catch (Evidence.LimitException e) {
            send(e.reason.equals("UPLOAD_LIMIT") ? 552 : 452, "Upload limit exceeded.");
        } catch (Exception e) {
            send(426, "Transfer aborted.");
        }
    }

    private Socket data(String message) throws IOException {
        ServerSocket listener = passive; passive = null;
        if (listener == null) { send(425, "Use PASV or EPSV first."); return null; }
        send(150, message);
        try (listener) {
            Socket data = listener.accept(); data.setSoTimeout(Trap21Application.DATA_SECONDS * 1000);
            if (!data.getInetAddress().equals(control.getInetAddress())) {
                data.close(); send(425, "Data source rejected."); return null;
            }
            return data;
        } catch (SocketTimeoutException e) { send(425, "Data connection timed out."); return null; }
    }

    private String path(String arg) {
        String raw = arg.startsWith("/") ? arg : cwd + "/" + arg;
        Deque<String> parts = new ArrayDeque<>();
        for (String part : raw.split("/+")) {
            if (part.isBlank() || part.equals(".")) continue;
            if (part.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); continue; }
            if (part.length() > 255 || part.chars().anyMatch(c -> c < 32 || c == '\\' || c == ':')) throw new IllegalArgumentException("Invalid path");
            parts.add(part);
        }
        return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
    }

    private String listing(String target) {
        if (FILES.containsKey(target)) return fileLine(target, FILES.get(target).length);
        if (!DIRS.contains(target)) return null;
        return switch (target) {
            case "/" -> dirLine("incoming") + dirLine("pub");
            case "/pub" -> fileLine("README.txt", FILES.get("/pub/README.txt").length)
                    + fileLine("partners.txt", FILES.get("/pub/partners.txt").length);
            case "/incoming" -> fileLine("INSTRUCTIONS.txt", FILES.get("/incoming/INSTRUCTIONS.txt").length);
            default -> null;
        };
    }

    private static String dirLine(String name) { return "drwxr-xr-x 1 ftp operations 0 Jan 01 00:00 " + name + "\r\n"; }
    private static String fileLine(String name, int size) {
        int slash = name.lastIndexOf('/'); if (slash >= 0) name = name.substring(slash + 1);
        return String.format(Locale.ROOT, "-rw-r--r-- 1 ftp operations %d Jan 01 00:00 %s\r\n", size, name);
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) != -1;) {
            if (c == '\n') return line.toString();
            if (c != '\r') { if (line.length() >= MAX_COMMAND) throw new IOException("Command too long"); line.append((char)c); }
        }
        return line.isEmpty() ? null : line.toString();
    }

    private void send(int code, String message) throws IOException { out.write(code + " " + message + "\r\n"); out.flush(); }
    private void closePassive() { if (passive != null) try { passive.close(); } catch (IOException ignored) {} passive = null; }
    private void log(String type) { evidence.log(type, "sessionId", id, "sourceIp", ip()); }
    private String ip() { return control.getInetAddress().getHostAddress(); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
