package com.zuomagai.redisproxy.dataplane.protocol;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;

public record ArgRef(ByteBuf raw, int offset, int length) {
    public String utf8() {
        return raw.toString(offset, length, StandardCharsets.UTF_8);
    }

    public String asciiUpper() {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            byte value = raw.getByte(offset + i);
            bytes[i] = value >= 'a' && value <= 'z' ? (byte) (value - 32) : value;
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    public byte[] copyBytes() {
        byte[] bytes = new byte[length];
        raw.getBytes(offset, bytes);
        return bytes;
    }

    public boolean equalsAsciiIgnoreCase(String value) {
        if (value.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            byte actual = raw.getByte(offset + i);
            byte expected = (byte) value.charAt(i);
            if (actual >= 'a' && actual <= 'z') {
                actual = (byte) (actual - 32);
            }
            if (expected >= 'a' && expected <= 'z') {
                expected = (byte) (expected - 32);
            }
            if (actual != expected) {
                return false;
            }
        }
        return true;
    }

    public boolean equalsUtf8(String value) {
        return equalsBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public boolean startsWithUtf8(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return true;
        }
        byte[] expected = prefix.getBytes(StandardCharsets.UTF_8);
        if (expected.length > length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (raw.getByte(offset + i) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    public boolean hashTagEqualsUtf8(String value) {
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);
        int[] range = hashTagRange();
        int tagOffset = range[0];
        int tagLength = range[1];
        if (expected.length != tagLength) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (raw.getByte(tagOffset + i) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    public boolean matchesGlobUtf8(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        byte[] expected = pattern.getBytes(StandardCharsets.UTF_8);
        int p = 0;
        int v = 0;
        int star = -1;
        int match = 0;
        while (v < length) {
            byte actual = raw.getByte(offset + v);
            if (p < expected.length && (expected[p] == '?' || expected[p] == actual)) {
                p++;
                v++;
                continue;
            }
            if (p < expected.length && expected[p] == '*') {
                star = p;
                match = v;
                p++;
                continue;
            }
            if (star >= 0) {
                p = star + 1;
                match++;
                v = match;
                continue;
            }
            return false;
        }
        while (p < expected.length && expected[p] == '*') {
            p++;
        }
        return p == expected.length;
    }

    public int slot() {
        return com.zuomagai.redisproxy.dataplane.router.RedisSlot.slot(raw, offset, length);
    }

    private boolean equalsBytes(byte[] expected) {
        if (expected.length != length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (raw.getByte(offset + i) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private int[] hashTagRange() {
        int start = -1;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (raw.getByte(i) == '{') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return new int[] {offset, length};
        }
        for (int i = start + 1; i < end; i++) {
            if (raw.getByte(i) == '}') {
                if (i == start + 1) {
                    return new int[] {offset, length};
                }
                return new int[] {start + 1, i - start - 1};
            }
        }
        return new int[] {offset, length};
    }
}
