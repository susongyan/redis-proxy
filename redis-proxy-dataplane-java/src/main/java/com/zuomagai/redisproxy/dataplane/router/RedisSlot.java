package com.zuomagai.redisproxy.dataplane.router;

import io.netty.buffer.ByteBuf;

public final class RedisSlot {
    public static final int SLOTS = 16384;

    private RedisSlot() {}

    public static int slot(byte[] key) {
        byte[] tag = hashTag(key);
        return crc16(tag) % SLOTS;
    }

    public static int slot(ByteBuf key, int offset, int length) {
        int[] tag = hashTag(key, offset, length);
        return crc16(key, tag[0], tag[1]) % SLOTS;
    }

    private static byte[] hashTag(byte[] key) {
        int start = -1;
        for (int i = 0; i < key.length; i++) {
            if (key[i] == '{') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return key;
        }
        for (int i = start + 1; i < key.length; i++) {
            if (key[i] == '}') {
                if (i == start + 1) {
                    return key;
                }
                byte[] tag = new byte[i - start - 1];
                System.arraycopy(key, start + 1, tag, 0, tag.length);
                return tag;
            }
        }
        return key;
    }

    private static int crc16(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xff) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xffff;
                } else {
                    crc = (crc << 1) & 0xffff;
                }
            }
        }
        return crc;
    }

    private static int[] hashTag(ByteBuf key, int offset, int length) {
        int start = -1;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (key.getByte(i) == '{') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return new int[] {offset, length};
        }
        for (int i = start + 1; i < end; i++) {
            if (key.getByte(i) == '}') {
                if (i == start + 1) {
                    return new int[] {offset, length};
                }
                return new int[] {start + 1, i - start - 1};
            }
        }
        return new int[] {offset, length};
    }

    private static int crc16(ByteBuf data, int offset, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            int b = data.getByte(offset + i) & 0xff;
            crc ^= b << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xffff;
                } else {
                    crc = (crc << 1) & 0xffff;
                }
            }
        }
        return crc;
    }
}
