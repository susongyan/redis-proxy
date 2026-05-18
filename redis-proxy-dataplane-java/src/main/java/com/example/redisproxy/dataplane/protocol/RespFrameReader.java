package com.example.redisproxy.dataplane.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class RespFrameReader {
    private RespFrameReader() {}

    public static byte[] read(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        readAny(in, out, maxBytes);
        return out.toByteArray();
    }

    private static void readAny(InputStream in, ByteArrayOutputStream out, int maxBytes) throws IOException {
        int prefix = in.read();
        if (prefix < 0) {
            throw new IOException("backend closed");
        }
        out.write(prefix);
        switch (prefix) {
            case '+', '-', ':' -> readLine(in, out);
            case '$' -> {
                int len = Integer.parseInt(readLine(in, out));
                if (len >= 0) {
                    copy(in, out, len + 2, maxBytes);
                }
            }
            case '*' -> {
                int count = Integer.parseInt(readLine(in, out));
                for (int i = 0; i < count; i++) {
                    readAny(in, out, maxBytes);
                }
            }
            default -> throw new IOException("invalid backend RESP frame");
        }
        if (maxBytes > 0 && out.size() > maxBytes) {
            throw new IOException("response frame exceeds " + maxBytes + " bytes");
        }
    }

    private static String readLine(InputStream in, ByteArrayOutputStream out) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("unexpected eof");
            }
            out.write(b);
            if (previous == '\r' && b == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, java.nio.charset.StandardCharsets.US_ASCII);
            }
            line.write(b);
            previous = b;
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, int bytes, int maxBytes) throws IOException {
        byte[] buffer = new byte[Math.min(8192, Math.max(bytes, 1))];
        int remaining = bytes;
        while (remaining > 0) {
            int n = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (n < 0) {
                throw new IOException("unexpected eof");
            }
            out.write(buffer, 0, n);
            remaining -= n;
            if (maxBytes > 0 && out.size() > maxBytes) {
                throw new IOException("response frame exceeds " + maxBytes + " bytes");
            }
        }
    }
}
