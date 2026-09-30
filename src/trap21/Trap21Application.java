package trap21;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;

public final class Trap21Application {
    static final int CONTROL_PORT = 2121, PASSIVE_START = 30000, PASSIVE_END = 30009;
    static final int IDLE_SECONDS = 120, DATA_SECONDS = 15, MAX_SESSIONS = 32;

    private Trap21Application() {}

    public static void main(String[] args) throws Exception {
        InetAddress bind = InetAddress.getByName(env("TRAP21_BIND", "127.0.0.1"));
        String publicHost = env("TRAP21_PUBLIC_HOST", "127.0.0.1");
        Evidence evidence = new Evidence(Path.of(env("TRAP21_DATA_DIR", "data")));
        Semaphore slots = new Semaphore(MAX_SESSIONS);

        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(bind, CONTROL_PORT));
            evidence.log("SERVER_STARTED", "port", server.getLocalPort());
            System.out.printf("TRAP21 listening on %s:%d%n", bind.getHostAddress(), server.getLocalPort());
            while (true) {
                Socket socket = server.accept();
                if (!slots.tryAcquire()) {
                    busy(socket);
                    continue;
                }
                Thread.ofVirtual().start(() -> {
                    try (socket) {
                        new ClientSession(bind, publicHost, socket, evidence).run();
                    } catch (Exception e) {
                        evidence.log("SESSION_FAILURE", "message", String.valueOf(e.getMessage()));
                    } finally {
                        slots.release();
                    }
                });
            }
        }
    }

    private static void busy(Socket socket) {
        try (socket; BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            out.write("421 Too many sessions.\r\n");
            out.flush();
        } catch (Exception ignored) {}
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
