package com.delrisco.trap21;

import java.util.List;

enum AccessProfile {
    ADMIN(List.of("/"), List.of("/")),
    BACKUP(List.of("/backups", "/archive", "/pub"), List.of("/backups/incoming")),
    TRANSFER(List.of("/incoming", "/outgoing", "/pub", "/archive/completed"),
            List.of("/incoming", "/outgoing")),
    SERVICE(List.of("/incoming", "/outgoing", "/pub", "/reports"),
            List.of("/incoming", "/outgoing")),
    OPERATOR(List.of("/reports", "/logs", "/archive", "/outgoing", "/pub"),
            List.of("/outgoing")),
    GUEST(List.of("/pub"), List.of()),
    ANONYMOUS(List.of("/pub"), List.of());

    private final List<String> readable;
    private final List<String> writable;

    AccessProfile(List<String> readable, List<String> writable) {
        this.readable = readable;
        this.writable = writable;
    }

    boolean canRead(String virtualPath) {
        return "/".equals(virtualPath) || readable.stream().anyMatch(prefix -> within(virtualPath, prefix));
    }

    boolean isVisible(String virtualPath) {
        if (canRead(virtualPath)) {
            return true;
        }
        return readable.stream().anyMatch(prefix -> within(prefix, virtualPath));
    }

    boolean canWrite(String virtualPath) {
        return writable.stream().anyMatch(prefix -> within(virtualPath, prefix));
    }

    private static boolean within(String path, String prefix) {
        if ("/".equals(prefix)) {
            return true;
        }
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
