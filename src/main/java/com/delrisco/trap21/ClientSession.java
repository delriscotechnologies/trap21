package com.delrisco.trap21;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class ClientSession {
    private static final String BANNER = "220 Authorized business use only";
    private static final DateTimeFormatter LIST_TIME = DateTimeFormatter
            .ofPattern("MMM dd HH:mm", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MDTM_TIME = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final Trap21Config config;
    private final Socket control;
    private final CredentialStore credentials;
    private final VirtualFileSystem fileSystem;
    private final JsonlEventLogger logger;
    private final String sessionId = UUID.randomUUID().toString();
    private final Instant connectedAt = Instant.now();
    private final AtomicReference<TransferState> activeTransfer = new AtomicReference<>();
    private BoundedLineReader reader;
    private BufferedWriter writer;
    private CredentialStore.UserAccount account;
    private String pendingUsername;
    private String currentDirectory = "/";
    private String renameFrom;
    private String transferType = "I";
    private String clientName = "unknown";
    private ServerSocket passiveListener;

    ClientSession(
            Trap21Config config,
            Socket control,
            CredentialStore credentials,
            VirtualFileSystem fileSystem,
            JsonlEventLogger logger) {
        this.config = config;
        this.control = control;
        this.credentials = credentials;
        this.fileSystem = fileSystem;
        this.logger = logger;
    }

    void run() throws IOException {
        reader = new BoundedLineReader(
                new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8),
                4096,
                control,
                config.idleTimeoutSeconds(),
                config.commandTimeoutSeconds());
        writer = new BufferedWriter(new OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8));
        log("CONNECT", Map.of("status", "OPEN"));
        sendRaw(BANNER);

        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    send(500, "Empty command.");
                    continue;
                }
                Command command = Command.parse(line);
                logCommand(command);
                if (!dispatch(command)) {
                    return;
                }
            }
        } catch (SocketTimeoutException exception) {
            send(421, "Control connection timed out.");
            log("TIMEOUT", Map.of("channel", "control"));
        } finally {
            cancelActiveTransfer(false);
            closePassive();
            log("DISCONNECT", Map.of(
                    "status", "CLOSED",
                    "durationMs", Duration.between(connectedAt, Instant.now()).toMillis()));
        }
    }

    private boolean dispatch(Command command) throws IOException {
        if (transferInProgress() && !allowsDuringTransfer(command.name())) {
            send(450, "A data transfer is already in progress; use ABOR first.");
            return true;
        }
        return switch (command.name()) {
            case "USER" -> user(command.argument());
            case "PASS" -> password(command.argument());
            case "QUIT" -> quit();
            case "NOOP" -> replyAndContinue(200, "NOOP command successful.");
            case "SYST" -> replyAndContinue(215, "UNIX Type: L8");
            case "FEAT" -> features();
            case "HELP" -> help();
            case "CLNT" -> client(command.argument());
            case "AUTH" -> replyAndContinue(502, "TLS is not available on this legacy gateway.");
            default -> authenticated(command);
        };
    }

    private boolean authenticated(Command command) throws IOException {
        if (account == null) {
            send(530, "Please login with USER and PASS.");
            return true;
        }
        return switch (command.name()) {
            case "PWD", "XPWD" -> printWorkingDirectory();
            case "CWD", "XCWD" -> changeDirectory(command.argument());
            case "CDUP", "XCUP" -> changeDirectory("..");
            case "TYPE" -> type(command.argument());
            case "MODE" -> simpleParameter(command.argument(), "S", "Mode set to S.");
            case "STRU" -> simpleParameter(command.argument(), "F", "Structure set to F.");
            case "OPTS" -> options(command.argument());
            case "PASV" -> passive(false);
            case "EPSV" -> passive(true);
            case "PORT", "EPRT" -> replyAndContinue(502, "Active mode is disabled; use PASV or EPSV.");
            case "LIST" -> list(command.argument(), false);
            case "NLST" -> list(command.argument(), true);
            case "RETR" -> retrieve(command.argument());
            case "STOR", "APPE" -> store(command.argument(), command.name());
            case "SIZE" -> size(command.argument());
            case "MDTM" -> modified(command.argument());
            case "MKD", "XMKD" -> makeDirectory(command.argument());
            case "RMD", "XRMD" -> removeDirectory(command.argument());
            case "DELE" -> delete(command.argument());
            case "RNFR" -> renameFrom(command.argument());
            case "RNTO" -> renameTo(command.argument());
            case "STAT" -> status();
            case "ABOR" -> abortTransfer();
            case "REST" -> replyAndContinue(502, "Restart markers are not supported.");
            case "PBSZ", "PROT" -> replyAndContinue(503, "Secure data channel is not active.");
            default -> replyAndContinue(502, "Command not implemented.");
        };
    }

    private boolean user(String username) throws IOException {
        if (username == null || username.isBlank()) {
            send(501, "USER requires a username.");
            return true;
        }
        pendingUsername = username.trim().toLowerCase(Locale.ROOT);
        account = null;
        currentDirectory = "/";
        send(331, "Password required for " + pendingUsername + ".");
        return true;
    }

    private boolean password(String presentedPassword) throws IOException {
        if (pendingUsername == null) {
            send(503, "Login with USER first.");
            return true;
        }
        String password = presentedPassword == null ? "" : presentedPassword;
        Optional<CredentialStore.UserAccount> authenticated = credentials.authenticate(pendingUsername, password);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("username", pendingUsername);
        event.put("presentedPassword", password);
        event.put("accepted", authenticated.isPresent());
        authenticated.ifPresent(value -> {
            event.put("passwordRank", value.passwordRank());
            event.put("profile", value.profile().name());
        });
        log("AUTH_ATTEMPT", event);
        if (authenticated.isEmpty()) {
            send(530, "Login incorrect.");
            return true;
        }
        account = authenticated.get();
        currentDirectory = "/";
        renameFrom = null;
        send(230, "User logged in, proceed.");
        return true;
    }

    private boolean quit() throws IOException {
        cancelActiveTransfer(false);
        send(221, "Goodbye.");
        return false;
    }

    private boolean client(String name) throws IOException {
        clientName = name == null || name.isBlank() ? "unknown" : name.trim();
        send(200, "Client accepted.");
        return true;
    }

    private boolean features() throws IOException {
        sendLines(List.of(
                "211-Extensions supported:",
                " EPSV",
                " MDTM",
                " PASV",
                " SIZE",
                " UTF8",
                "211 End"));
        return true;
    }

    private boolean help() throws IOException {
        sendLines(List.of(
                "214-The following commands are recognized:",
                " USER PASS QUIT SYST FEAT PWD CWD CDUP TYPE",
                " PASV EPSV LIST NLST RETR STOR APPE SIZE MDTM",
                " MKD RMD DELE RNFR RNTO ABOR NOOP HELP STAT",
                "214 Help OK."));
        return true;
    }

    private boolean printWorkingDirectory() throws IOException {
        send(257, "\"" + currentDirectory.replace("\"", "\"\"") + "\" is current directory.");
        return true;
    }

    private boolean changeDirectory(String argument) throws IOException {
        try {
            String target = fileSystem.resolve(currentDirectory, argument);
            if (!account.profile().canRead(target) || !fileSystem.isDirectory(target)) {
                send(550, "Failed to change directory.");
                return true;
            }
            currentDirectory = target;
            send(250, "Directory successfully changed.");
        } catch (IOException exception) {
            send(550, "Failed to change directory.");
        }
        return true;
    }

    private boolean type(String argument) throws IOException {
        String requested = argument == null ? "" : argument.trim().toUpperCase(Locale.ROOT);
        if (!(requested.equals("A") || requested.equals("I") || requested.equals("L 8") || requested.equals("L8"))) {
            send(504, "Unsupported TYPE parameter.");
            return true;
        }
        transferType = requested.startsWith("A") ? "A" : "I";
        send(200, "Type set to " + transferType + ".");
        return true;
    }

    private boolean simpleParameter(String argument, String accepted, String message) throws IOException {
        if (argument != null && accepted.equalsIgnoreCase(argument.trim())) {
            send(200, message);
        } else {
            send(504, "Unsupported parameter.");
        }
        return true;
    }

    private boolean options(String argument) throws IOException {
        if (argument != null && "UTF8 ON".equalsIgnoreCase(argument.trim())) {
            send(200, "UTF8 mode enabled.");
        } else {
            send(501, "Unsupported option.");
        }
        return true;
    }

    private boolean passive(boolean extended) throws IOException {
        closePassive();
        passiveListener = bindPassiveListener();
        int port = passiveListener.getLocalPort();
        if (extended) {
            send(229, "Entering Extended Passive Mode (|||" + port + "|).");
        } else {
            InetAddress advertised = advertisedAddress();
            byte[] octets = advertised.getAddress();
            send(227, String.format(
                    Locale.ROOT,
                    "Entering Passive Mode (%d,%d,%d,%d,%d,%d).",
                    octets[0] & 0xff,
                    octets[1] & 0xff,
                    octets[2] & 0xff,
                    octets[3] & 0xff,
                    port / 256,
                    port % 256));
        }
        log("PASSIVE_LISTENER", Map.of("port", port, "extended", extended));
        return true;
    }

    private ServerSocket bindPassiveListener() throws IOException {
        IOException lastFailure = null;
        if (config.passivePortStart() == 0 && config.passivePortEnd() == 0) {
            ServerSocket listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(config.bindAddress(), 0));
            listener.setSoTimeout(config.dataTimeoutSeconds() * 1000);
            return listener;
        }
        for (int port = config.passivePortStart(); port <= config.passivePortEnd(); port++) {
            try {
                ServerSocket listener = new ServerSocket();
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(config.bindAddress(), port));
                listener.setSoTimeout(config.dataTimeoutSeconds() * 1000);
                return listener;
            } catch (IOException exception) {
                lastFailure = exception;
            }
        }
        throw new IOException("No passive ports are available", lastFailure);
    }

    private InetAddress advertisedAddress() throws IOException {
        InetAddress selected = config.publicHost().isBlank()
                ? control.getLocalAddress()
                : InetAddress.getByName(config.publicHost());
        if (selected instanceof Inet4Address && !selected.isAnyLocalAddress()) {
            return selected;
        }
        InetAddress peerLocal = control.getLocalAddress();
        if (peerLocal instanceof Inet4Address && !peerLocal.isAnyLocalAddress()) {
            return peerLocal;
        }
        return InetAddress.getByName("127.0.0.1");
    }

    private boolean list(String argument, boolean namesOnly) throws IOException {
        boolean includeHidden = argument != null && argument.contains("a");
        String pathArgument = listPath(argument);
        String target;
        try {
            target = fileSystem.resolve(currentDirectory, pathArgument);
            if (!account.profile().canRead(target) || !fileSystem.exists(target)) {
                send(550, "Requested action not taken.");
                return true;
            }
        } catch (IOException exception) {
            send(550, "Requested action not taken.");
            return true;
        }

        List<VirtualFileSystem.FileEntry> entries = fileSystem.list(target, includeHidden).stream()
                .filter(entry -> account.profile().isVisible(entry.virtualPath()))
                .toList();
        Socket data = openDataConnection("Opening ASCII mode data connection for file list.");
        if (data == null) {
            return true;
        }
        String command = namesOnly ? "NLST" : "LIST";
        beginTransfer(command, data, state -> {
            long bytes = 0;
            try (OutputStream output = data.getOutputStream()) {
                for (VirtualFileSystem.FileEntry entry : entries) {
                    String line = namesOnly ? entry.name() + "\r\n" : listingLine(entry);
                    byte[] encoded = line.getBytes(StandardCharsets.UTF_8);
                    output.write(encoded);
                    bytes += encoded.length;
                }
                output.flush();
            }
            if (state.tryComplete()) {
                send(226, "Transfer complete.");
                log("LIST", Map.of("path", target, "entries", entries.size(), "bytes", bytes));
            }
        });
        return true;
    }

    private boolean retrieve(String argument) throws IOException {
        String target;
        try {
            target = fileSystem.resolve(currentDirectory, argument);
            if (!account.profile().canRead(target) || fileSystem.isDirectory(target)) {
                send(550, "Failed to open file.");
                return true;
            }
        } catch (IOException exception) {
            send(550, "Failed to open file.");
            return true;
        }
        Socket data = openDataConnection("Opening " + ("A".equals(transferType) ? "ASCII" : "BINARY")
                + " mode data connection.");
        if (data == null) {
            return true;
        }
        String selectedType = transferType;
        beginTransfer("RETR", data, state -> {
            long bytes;
            try (InputStream source = fileSystem.openForRead(target);
                    InputStream input = "A".equals(selectedType)
                            ? new NvtAsciiInputStream(source, NvtAsciiInputStream.Direction.LOCAL_TO_NETWORK)
                            : source;
                    OutputStream output = data.getOutputStream()) {
                bytes = copy(input, output);
                output.flush();
            }
            if (state.tryComplete()) {
                send(226, "Transfer complete.");
                log("DOWNLOAD", Map.of("path", target, "bytes", bytes, "status", "SUCCESS"));
            }
        });
        return true;
    }

    private boolean store(String argument, String command) throws IOException {
        String target;
        try {
            target = fileSystem.resolve(currentDirectory, argument);
            if (!account.profile().canWrite(target)) {
                send(550, "Permission denied.");
                return true;
            }
        } catch (IOException exception) {
            send(550, "Invalid target path.");
            return true;
        }
        String selectedType = transferType;
        Socket data = openDataConnection("Opening " + ("A".equals(selectedType) ? "ASCII" : "BINARY")
                + " mode data connection for upload.");
        if (data == null) {
            return true;
        }
        beginTransfer(command, data, state -> performStore(state, data, target, command, selectedType));
        return true;
    }

    private boolean size(String argument) throws IOException {
        try {
            String target = fileSystem.resolve(currentDirectory, argument);
            if (!account.profile().canRead(target)) {
                throw new NoSuchFileException(target);
            }
            send(213, String.valueOf(fileSystem.size(target)));
        } catch (IOException exception) {
            send(550, "Could not get file size.");
        }
        return true;
    }

    private boolean modified(String argument) throws IOException {
        try {
            String target = fileSystem.resolve(currentDirectory, argument);
            if (!account.profile().canRead(target)) {
                throw new NoSuchFileException(target);
            }
            send(213, MDTM_TIME.format(fileSystem.modified(target)));
        } catch (IOException exception) {
            send(550, "Could not get modification time.");
        }
        return true;
    }

    private boolean makeDirectory(String argument) throws IOException {
        try {
            String target = fileSystem.resolve(currentDirectory, argument);
            if (!account.profile().canWrite(target)) {
                send(550, "Permission denied.");
                return true;
            }
            fileSystem.makeDirectory(target);
            send(257, "\"" + target + "\" created.");
            log("MKD", Map.of("path", target, "status", "SUCCESS"));
        } catch (IOException exception) {
            send(550, "Create directory operation failed.");
        }
        return true;
    }

    private boolean removeDirectory(String argument) throws IOException {
        try {
            String target = fileSystem.resolve(currentDirectory, argument);
            if (!account.profile().canWrite(target)) {
                send(550, "Permission denied.");
                return true;
            }
            fileSystem.removeDirectory(target);
            send(250, "Remove directory operation successful.");
            log("RMD", Map.of("path", target, "status", "SUCCESS"));
        } catch (IOException exception) {
            send(550, "Remove directory operation failed.");
        }
        return true;
    }

    private boolean delete(String argument) throws IOException {
        try {
            String target = fileSystem.resolve(currentDirectory, argument);
            if (!account.profile().canWrite(target)) {
                send(550, "Permission denied.");
                return true;
            }
            fileSystem.delete(target);
            send(250, "Delete operation successful.");
            log("DELE", Map.of("path", target, "status", "SUCCESS"));
        } catch (IOException exception) {
            send(550, "Delete operation failed.");
        }
        return true;
    }

    private boolean renameFrom(String argument) throws IOException {
        try {
            String target = fileSystem.resolve(currentDirectory, argument);
            if (!account.profile().canWrite(target) || !fileSystem.exists(target)) {
                send(550, "File unavailable.");
                return true;
            }
            renameFrom = target;
            send(350, "Ready for RNTO.");
        } catch (IOException exception) {
            send(550, "File unavailable.");
        }
        return true;
    }

    private boolean renameTo(String argument) throws IOException {
        if (renameFrom == null) {
            send(503, "RNFR required first.");
            return true;
        }
        String source = renameFrom;
        renameFrom = null;
        try {
            String target = fileSystem.resolve(currentDirectory, argument);
            if (!account.profile().canWrite(target)) {
                send(550, "Permission denied.");
                return true;
            }
            fileSystem.rename(source, target);
            send(250, "Rename successful.");
            log("RENAME", Map.of("source", source, "target", target, "status", "SUCCESS"));
        } catch (IOException exception) {
            send(550, "Rename failed.");
        }
        return true;
    }

    private boolean status() throws IOException {
        sendLines(List.of(
                "211-Managed File Transfer Gateway status:",
                " Connected from " + sourceIp(),
                " Logged in as " + account.username(),
                " TYPE: " + transferType,
                " Client: " + clientName,
                "211 End of status"));
        return true;
    }

    private boolean abortTransfer() throws IOException {
        if (cancelActiveTransfer(true)) {
            return true;
        }
        closePassive();
        send(225, "No transfer is in progress.");
        return true;
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
            data.setSoTimeout(config.dataTimeoutSeconds() * 1000);
            if (!data.getInetAddress().equals(control.getInetAddress())) {
                data.close();
                send(425, "Data connection source does not match control connection.");
                log("DATA_REJECTED", Map.of("sourceIp", data.getInetAddress().getHostAddress()));
                return null;
            }
            return data;
        } catch (SocketTimeoutException exception) {
            send(425, "Data connection timed out.");
            log("TIMEOUT", Map.of("channel", "data"));
            return null;
        }
    }

    private void beginTransfer(String command, Socket data, TransferTask task) throws IOException {
        TransferState state = new TransferState(data);
        Thread worker = Thread.ofVirtual().name("trap21-transfer-" + sessionId).unstarted(() -> {
            try (data) {
                task.run(state);
            } catch (IOException exception) {
                if (state.tryComplete()) {
                    try {
                        send(426, "Connection closed; transfer aborted.");
                    } catch (IOException ignored) {
                        // The control connection may already be closed.
                    }
                    log("DATA_FAILURE", Map.of(
                            "command", command,
                            "message", String.valueOf(exception.getMessage())));
                }
            } catch (RuntimeException exception) {
                if (state.tryComplete()) {
                    try {
                        send(451, "Requested action aborted; local error in processing.");
                    } catch (IOException ignored) {
                        // The control connection may already be closed.
                    }
                    log("DATA_FAILURE", Map.of(
                            "command", command,
                            "message", String.valueOf(exception.getMessage())));
                }
            } finally {
                activeTransfer.compareAndSet(state, null);
            }
        });
        state.worker(worker);
        if (!activeTransfer.compareAndSet(null, state)) {
            data.close();
            send(450, "A data transfer is already in progress; use ABOR first.");
            return;
        }
        worker.start();
    }

    private void performStore(
            TransferState state,
            Socket data,
            String target,
            String command,
            String selectedType) throws IOException {
        try (InputStream networkInput = data.getInputStream();
                InputStream input = "A".equals(selectedType)
                        ? new NvtAsciiInputStream(networkInput, NvtAsciiInputStream.Direction.NETWORK_TO_LOCAL)
                        : networkInput) {
            VirtualFileSystem.CapturedUpload capture = fileSystem.capture(
                    sessionId,
                    target,
                    input,
                    config.maxUploadBytes(),
                    "APPE".equals(command));
            if (state.tryComplete()) {
                send(226, "Transfer complete.");
                log("UPLOAD", Map.of(
                        "command", command,
                        "path", target,
                        "bytes", capture.size(),
                        "sha256", capture.sha256(),
                        "quarantineFile", capture.quarantineFile().toString(),
                        "status", "CAPTURED"));
            }
        } catch (VirtualFileSystem.UploadTooLargeException exception) {
            if (state.tryComplete()) {
                send(552, "Requested file action aborted; file limit exceeded.");
                log("UPLOAD", Map.of("path", target, "status", "FILE_LIMIT_EXCEEDED"));
            }
        } catch (VirtualFileSystem.StorageQuotaExceededException exception) {
            if (state.tryComplete()) {
                send(452, "Requested action not taken; quarantine capacity exceeded.");
                log("UPLOAD", Map.of("path", target, "status", "QUARANTINE_LIMIT_EXCEEDED"));
            }
        } catch (FileAlreadyExistsException exception) {
            if (state.tryComplete()) {
                send(550, "Target file cannot be replaced.");
                log("UPLOAD", Map.of("path", target, "status", "REJECTED"));
            }
        } catch (IOException exception) {
            if (state.tryComplete()) {
                send(426, "Connection closed; transfer aborted.");
                log("UPLOAD", Map.of(
                        "path", target,
                        "status", "FAILED",
                        "message", String.valueOf(exception.getMessage())));
            }
        }
    }

    private static long copy(InputStream input, OutputStream output) throws IOException {
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            total += count;
        }
        return total;
    }

    private boolean transferInProgress() {
        return activeTransfer.get() != null;
    }

    private static boolean allowsDuringTransfer(String command) {
        return "ABOR".equals(command) || "QUIT".equals(command) || "NOOP".equals(command) || "STAT".equals(command);
    }

    private boolean cancelActiveTransfer(boolean reply) throws IOException {
        TransferState state = activeTransfer.get();
        if (state == null || !state.tryAbort()) {
            return false;
        }
        state.closeData();
        Thread worker = state.worker();
        if (worker != null) {
            worker.interrupt();
        }
        if (reply) {
            send(426, "Connection closed; transfer aborted.");
            send(226, "Abort command successful.");
        }
        if (worker != null) {
            try {
                worker.join(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        activeTransfer.compareAndSet(state, null);
        return true;
    }

    private String listingLine(VirtualFileSystem.FileEntry entry) {
        String permissions = entry.directory() ? "drwxr-xr-x" : "-rw-r--r--";
        return String.format(
                Locale.ENGLISH,
                "%s 1 %-12s %-12s %10d %s %s\r\n",
                permissions,
                account.username(),
                "operations",
                entry.size(),
                LIST_TIME.format(entry.modified()),
                entry.name());
    }

    private String listPath(String argument) {
        if (argument == null || argument.isBlank()) {
            return currentDirectory;
        }
        String[] tokens = argument.trim().split("\\s+");
        for (int index = tokens.length - 1; index >= 0; index--) {
            if (!tokens[index].startsWith("-")) {
                return tokens[index];
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
                // The passive listener is best-effort cleanup.
            }
        }
    }

    private boolean replyAndContinue(int code, String message) throws IOException {
        send(code, message);
        return true;
    }

    private void send(int code, String message) throws IOException {
        sendRaw(code + " " + message);
    }

    private void sendLines(List<String> lines) throws IOException {
        for (String line : lines) {
            sendRaw(line);
        }
    }

    private synchronized void sendRaw(String line) throws IOException {
        writer.write(line);
        writer.write("\r\n");
        writer.flush();
    }

    private void logCommand(Command command) {
        log("COMMAND", Map.of(
                "command", command.name(),
                "argument", "PASS".equals(command.name()) ? "<redacted>" : command.argumentOrEmpty()));
    }

    private void log(String eventType, Map<String, ?> values) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sessionId", sessionId);
        event.put("sourceIp", sourceIp());
        event.put("sourcePort", control.getPort());
        event.put("username", account == null ? pendingUsername : account.username());
        event.putAll(values);
        logger.log(eventType, event);
    }

    private String sourceIp() {
        return control.getInetAddress().getHostAddress();
    }

    private record Command(String name, String argument) {
        static Command parse(String line) {
            int separator = line.indexOf(' ');
            String name = (separator < 0 ? line : line.substring(0, separator)).trim().toUpperCase(Locale.ROOT);
            String argument = separator < 0 ? null : line.substring(separator + 1).trim();
            return new Command(name, argument);
        }

        String argumentOrEmpty() {
            return argument == null ? "" : argument;
        }
    }

    @FunctionalInterface
    private interface TransferTask {
        void run(TransferState state) throws IOException;
    }

    private enum TransferStatus {
        ACTIVE,
        COMPLETED,
        ABORTED
    }

    private static final class TransferState {
        private final Socket data;
        private final AtomicReference<TransferStatus> status = new AtomicReference<>(TransferStatus.ACTIVE);
        private volatile Thread worker;

        TransferState(Socket data) {
            this.data = data;
        }

        boolean tryComplete() {
            return status.compareAndSet(TransferStatus.ACTIVE, TransferStatus.COMPLETED);
        }

        boolean tryAbort() {
            return status.compareAndSet(TransferStatus.ACTIVE, TransferStatus.ABORTED);
        }

        void closeData() {
            try {
                data.close();
            } catch (IOException ignored) {
                // Closing an aborted data socket is best-effort.
            }
        }

        Thread worker() {
            return worker;
        }

        void worker(Thread worker) {
            this.worker = worker;
        }
    }
}
