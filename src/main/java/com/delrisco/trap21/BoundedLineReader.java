package com.delrisco.trap21;

import java.io.IOException;
import java.io.Reader;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

final class BoundedLineReader {
    private final Reader reader;
    private final int maximumLength;
    private final Socket socket;
    private final int idleTimeoutMillis;
    private final long commandTimeoutNanos;

    BoundedLineReader(
            Reader reader,
            int maximumLength,
            Socket socket,
            int idleTimeoutSeconds,
            int commandTimeoutSeconds) {
        this.reader = reader;
        this.maximumLength = maximumLength;
        this.socket = socket;
        this.idleTimeoutMillis = Math.multiplyExact(idleTimeoutSeconds, 1000);
        this.commandTimeoutNanos = TimeUnit.SECONDS.toNanos(commandTimeoutSeconds);
    }

    String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        socket.setSoTimeout(idleTimeoutMillis);
        int value = reader.read();
        if (value == -1) {
            return null;
        }
        long deadline = System.nanoTime() + commandTimeoutNanos;
        while (true) {
            if (value == '\n') {
                return line.toString();
            }
            if (value == '\r') {
                int terminator = readBefore(deadline);
                if (terminator == '\n') {
                    return line.toString();
                }
                throw new IOException("FTP commands must end with CRLF");
            }
            if (line.length() >= maximumLength) {
                throw new IOException("FTP command exceeds " + maximumLength + " characters");
            }
            line.append((char) value);
            value = readBefore(deadline);
            if (value == -1) {
                return line.toString();
            }
        }
    }

    private int readBefore(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("FTP command deadline exceeded");
        }
        long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
        socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMillis));
        return reader.read();
    }
}
