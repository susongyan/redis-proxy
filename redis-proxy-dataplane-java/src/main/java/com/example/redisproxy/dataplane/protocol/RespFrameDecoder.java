package com.example.redisproxy.dataplane.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

public class RespFrameDecoder extends ByteToMessageDecoder {
    private final int maxFrameBytes;

    public RespFrameDecoder(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int start = in.readerIndex();
        int end = frameEnd(in, start);
        if (end < 0) {
            return;
        }
        int len = end - start;
        if (maxFrameBytes > 0 && len > maxFrameBytes) {
            in.readerIndex(end);
            throw new IllegalArgumentException("RESP frame exceeds " + maxFrameBytes + " bytes");
        }
        out.add(in.retainedSlice(start, len));
        in.readerIndex(end);
    }

    private static int frameEnd(ByteBuf in, int index) {
        if (index >= in.writerIndex()) {
            return -1;
        }
        byte prefix = in.getByte(index);
        return switch (prefix) {
            case '+', '-', ':' -> lineEnd(in, index + 1);
            case '$' -> bulkEnd(in, index + 1);
            case '*' -> arrayEnd(in, index + 1);
            default -> throw new IllegalArgumentException("invalid RESP frame prefix: " + (char) prefix);
        };
    }

    private static int bulkEnd(ByteBuf in, int index) {
        int lineEnd = lineEnd(in, index);
        if (lineEnd < 0) {
            return -1;
        }
        int len = parseInt(in, index, lineEnd - 2);
        if (len < 0) {
            return lineEnd;
        }
        int end = lineEnd + len + 2;
        if (in.writerIndex() < end) {
            return -1;
        }
        if (in.getByte(end - 2) != '\r' || in.getByte(end - 1) != '\n') {
            throw new IllegalArgumentException("invalid RESP bulk terminator");
        }
        return end;
    }

    private static int arrayEnd(ByteBuf in, int index) {
        int lineEnd = lineEnd(in, index);
        if (lineEnd < 0) {
            return -1;
        }
        int count = parseInt(in, index, lineEnd - 2);
        if (count < 0) {
            return lineEnd;
        }
        int cursor = lineEnd;
        for (int i = 0; i < count; i++) {
            cursor = frameEnd(in, cursor);
            if (cursor < 0) {
                return -1;
            }
        }
        return cursor;
    }

    private static int lineEnd(ByteBuf in, int index) {
        for (int i = index; i + 1 < in.writerIndex(); i++) {
            if (in.getByte(i) == '\r' && in.getByte(i + 1) == '\n') {
                return i + 2;
            }
        }
        return -1;
    }

    private static int parseInt(ByteBuf in, int start, int endExclusive) {
        boolean negative = false;
        int index = start;
        if (index < endExclusive && in.getByte(index) == '-') {
            negative = true;
            index++;
        }
        int value = 0;
        for (; index < endExclusive; index++) {
            byte b = in.getByte(index);
            if (b < '0' || b > '9') {
                throw new IllegalArgumentException("invalid RESP integer");
            }
            value = value * 10 + (b - '0');
        }
        return negative ? -value : value;
    }
}
