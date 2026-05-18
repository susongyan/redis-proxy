package com.example.redisproxy.dataplane.router;

public final class RedisSlot {
    public static final int SLOTS = 16384;

    private RedisSlot() {}

    public static int slot(byte[] key) {
        byte[] tag = hashTag(key);
        return crc16(tag) % SLOTS;
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
}
