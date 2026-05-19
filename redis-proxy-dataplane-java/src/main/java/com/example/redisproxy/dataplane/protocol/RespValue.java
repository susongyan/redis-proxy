package com.example.redisproxy.dataplane.protocol;

import java.util.List;

public record RespValue(Kind kind, byte[] bytes, long integer, List<RespValue> array) {
    public enum Kind {
        SIMPLE_STRING,
        ERROR,
        INTEGER,
        BULK_STRING,
        ARRAY
    }

    public static RespValue simpleString(byte[] bytes) {
        return new RespValue(Kind.SIMPLE_STRING, bytes, 0, List.of());
    }

    public static RespValue error(byte[] bytes) {
        return new RespValue(Kind.ERROR, bytes, 0, List.of());
    }

    public static RespValue integer(long integer) {
        return new RespValue(Kind.INTEGER, null, integer, List.of());
    }

    public static RespValue bulkString(byte[] bytes) {
        return new RespValue(Kind.BULK_STRING, bytes, 0, List.of());
    }

    public static RespValue array(List<RespValue> array) {
        return new RespValue(Kind.ARRAY, null, 0, List.copyOf(array));
    }
}
