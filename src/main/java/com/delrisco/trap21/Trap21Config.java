package com.delrisco.trap21;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Map;

public record Trap21Config(
        InetAddress bindAddress,
        int controlPort,
        int passivePortStart,
        int passivePortEnd,
        String publicHost,
        Path dataDirectory,
        int idleTimeoutSeconds,
        int dataTimeoutSeconds,
        long maxUploadBytes,
        int maxSessions) {

    public Trap21Config {
        if (controlPort < 0 || controlPort > 65_535) {
            throw new IllegalArgumentException("controlPort must be between 0 and 65535");
        }
        if (passivePortStart < 0 || passivePortEnd > 65_535 || passivePortEnd < passivePortStart) {
            throw new IllegalArgumentException("Invalid passive port range");
        }
        if (idleTimeoutSeconds < 1 || dataTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
        if (maxUploadBytes < 1 || maxSessions < 1) {
            throw new IllegalArgumentException("Limits must be positive");
        }
        publicHost = publicHost == null ? "" : publicHost.trim();
        dataDirectory = dataDirectory.toAbsolutePath().normalize();
    }

    public static Trap21Config fromEnvironment() {
        Map<String, String> env = System.getenv();
        try {
            return new Trap21Config(
                    InetAddress.getByName(env.getOrDefault("TRAP21_BIND", "0.0.0.0")),
                    integer(env, "TRAP21_PORT", 2121),
                    integer(env, "TRAP21_PASSIVE_START", 30000),
                    integer(env, "TRAP21_PASSIVE_END", 30009),
                    env.getOrDefault("TRAP21_PUBLIC_HOST", ""),
                    Path.of(env.getOrDefault("TRAP21_DATA_DIR", "data")),
                    integer(env, "TRAP21_IDLE_TIMEOUT", 120),
                    integer(env, "TRAP21_DATA_TIMEOUT", 15),
                    longInteger(env, "TRAP21_MAX_UPLOAD_BYTES", 10L * 1024 * 1024),
                    integer(env, "TRAP21_MAX_SESSIONS", 64));
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Invalid TRAP21_BIND value", exception);
        }
    }

    private static int integer(Map<String, String> env, String name, int fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static long longInteger(Map<String, String> env, String name, long fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value);
    }
}
