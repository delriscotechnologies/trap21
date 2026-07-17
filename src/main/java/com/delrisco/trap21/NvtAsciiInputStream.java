package com.delrisco.trap21;

import java.io.IOException;
import java.io.InputStream;

final class NvtAsciiInputStream extends InputStream {
    enum Direction {
        LOCAL_TO_NETWORK,
        NETWORK_TO_LOCAL
    }

    private final InputStream source;
    private final Direction direction;
    private int pendingFirst = -1;
    private int pendingSecond = -1;

    NvtAsciiInputStream(InputStream source, Direction direction) {
        this.source = source;
        this.direction = direction;
    }

    @Override
    public int read() throws IOException {
        if (pendingFirst >= 0) {
            int value = pendingFirst;
            pendingFirst = pendingSecond;
            pendingSecond = -1;
            return value;
        }
        return direction == Direction.LOCAL_TO_NETWORK
                ? readForNetwork()
                : readForLocalStorage();
    }

    private int readForNetwork() throws IOException {
        int current = source.read();
        if (current == '\n') {
            pendingFirst = '\n';
            return '\r';
        }
        if (current != '\r') {
            return current;
        }

        int next = source.read();
        if (next == '\n') {
            pendingFirst = '\n';
        } else if (next == 0) {
            pendingFirst = 0;
        } else {
            pendingFirst = 0;
            pendingSecond = next;
        }
        return '\r';
    }

    private int readForLocalStorage() throws IOException {
        int current = source.read();
        if (current != '\r') {
            return current;
        }

        int next = source.read();
        if (next == '\n') {
            return '\n';
        }
        if (next != 0) {
            pendingFirst = next;
        }
        return '\r';
    }
}
