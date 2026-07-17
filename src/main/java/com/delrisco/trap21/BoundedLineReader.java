package com.delrisco.trap21;

import java.io.IOException;
import java.io.Reader;

final class BoundedLineReader {
    private final Reader reader;
    private final int maximumLength;

    BoundedLineReader(Reader reader, int maximumLength) {
        this.reader = reader;
        this.maximumLength = maximumLength;
    }

    String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int value = reader.read();
            if (value == -1) {
                return line.isEmpty() ? null : line.toString();
            }
            if (value == '\n') {
                return line.toString();
            }
            if (value == '\r') {
                continue;
            }
            if (line.length() >= maximumLength) {
                throw new IOException("FTP command exceeds " + maximumLength + " characters");
            }
            line.append((char) value);
        }
    }
}
