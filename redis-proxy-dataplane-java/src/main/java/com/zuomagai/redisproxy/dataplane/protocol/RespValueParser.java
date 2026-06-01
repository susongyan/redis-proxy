package com.zuomagai.redisproxy.dataplane.protocol;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RespValueParser {
    private final byte[] data;
    private int index;

    private RespValueParser(ByteBuf buffer) {
        this.data = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), data);
    }

    public static RespValue parse(ByteBuf buffer) {
        RespValueParser parser = new RespValueParser(buffer);
        RespValue value = parser.parseValue();
        if (parser.index != parser.data.length) {
            throw new IllegalArgumentException("trailing RESP bytes");
        }
        return value;
    }

    private RespValue parseValue() {
        if (index >= data.length) {
            throw new IllegalArgumentException("empty RESP value");
        }
        byte prefix = data[index++];
        return switch (prefix) {
            case '+' -> RespValue.simpleString(readLineBytes());
            case '-' -> RespValue.error(readLineBytes());
            case ':' -> RespValue.integer(readLong());
            case '$' -> readBulkString();
            case '*' -> readArray();
            default -> throw new IllegalArgumentException("invalid RESP prefix: " + (char) prefix);
        };
    }

    private RespValue readBulkString() {
        int len = (int) readLong();
        if (len < 0) {
            return RespValue.bulkString(null);
        }
        require(index + len + 2 <= data.length, "incomplete bulk string");
        byte[] value = Arrays.copyOfRange(data, index, index + len);
        index += len;
        readCrlf();
        return RespValue.bulkString(value);
    }

    private RespValue readArray() {
        int count = (int) readLong();
        if (count < 0) {
            return RespValue.array(List.of());
        }
        List<RespValue> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(parseValue());
        }
        return RespValue.array(values);
    }

    private long readLong() {
        byte[] line = readLineBytes();
        boolean negative = false;
        int cursor = 0;
        if (line.length > 0 && line[0] == '-') {
            negative = true;
            cursor = 1;
        }
        long value = 0;
        for (; cursor < line.length; cursor++) {
            byte b = line[cursor];
            if (b < '0' || b > '9') {
                throw new IllegalArgumentException("invalid RESP integer");
            }
            value = value * 10 + (b - '0');
        }
        return negative ? -value : value;
    }

    private byte[] readLineBytes() {
        int start = index;
        while (index + 1 < data.length) {
            if (data[index] == '\r' && data[index + 1] == '\n') {
                byte[] line = Arrays.copyOfRange(data, start, index);
                index += 2;
                return line;
            }
            index++;
        }
        throw new IllegalArgumentException("missing RESP line terminator");
    }

    private void readCrlf() {
        require(index + 2 <= data.length, "missing RESP terminator");
        if (data[index] != '\r' || data[index + 1] != '\n') {
            throw new IllegalArgumentException("invalid RESP terminator");
        }
        index += 2;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
