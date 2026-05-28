package com.example.redisproxy.dataplane.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;
import java.util.ArrayList;

public class RespRequestDecoder extends ByteToMessageDecoder {
    private final int maxRequestBytes;

    public RespRequestDecoder(int maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int start = in.readerIndex();
        try {
            if (!in.isReadable() || in.getByte(start) != '*') {
                return;
            }
            int cursor = start + 1;
            LineInt arrayLine = readLineInt(in, cursor);
            if (arrayLine == null) {
                return;
            }
            int count = arrayLine.value();
            if (count <= 0) {
                throw new IllegalArgumentException("invalid array length");
            }
            cursor = arrayLine.nextIndex();
            int[] offsets = new int[count];
            int[] lengths = new int[count];
            for (int i = 0; i < count; i++) {
                if (cursor >= in.writerIndex() || in.getByte(cursor) != '$') {
                    return;
                }
                LineInt bulkLine = readLineInt(in, cursor + 1);
                if (bulkLine == null) {
                    return;
                }
                int size = bulkLine.value();
                if (size < 0) {
                    throw new IllegalArgumentException("invalid bulk length");
                }
                cursor = bulkLine.nextIndex();
                if (in.writerIndex() < cursor + size + 2) {
                    return;
                }
                if (in.getByte(cursor + size) != '\r' || in.getByte(cursor + size + 1) != '\n') {
                    throw new IllegalArgumentException("invalid bulk terminator");
                }
                offsets[i] = cursor - start;
                lengths[i] = size;
                cursor += size + 2;
            }
            int len = cursor - start;
            if (len > maxRequestBytes) {
                ctx.writeAndFlush(Unpooled.copiedBuffer(("-ERR request frame exceeds " + maxRequestBytes + "\r\n").getBytes()));
                in.readerIndex(cursor);
                return;
            }
            ByteBuf raw = in.retainedSlice(start, len);
            List<ArgRef> args = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                args.add(new ArgRef(raw, offsets[i], lengths[i]));
            }
            in.readerIndex(cursor);
            out.add(RespRequest.fromArgRefs(raw, args));
        } catch (RuntimeException e) {
            in.readerIndex(in.writerIndex());
            ctx.writeAndFlush(Unpooled.copiedBuffer("-ERR invalid RESP frame\r\n".getBytes()));
        }
    }

    private static LineInt readLineInt(ByteBuf in, int index) {
        int value = 0;
        boolean negative = false;
        if (index < in.writerIndex() && in.getByte(index) == '-') {
            negative = true;
            index++;
        }
        boolean hasDigit = false;
        for (int i = index; i + 1 < in.writerIndex(); i++) {
            byte next = in.getByte(i);
            if (next == '\r' && in.getByte(i + 1) == '\n') {
                if (!hasDigit) {
                    throw new IllegalArgumentException("empty integer");
                }
                return new LineInt(negative ? -value : value, i + 2);
            }
            if (next < '0' || next > '9') {
                throw new IllegalArgumentException("invalid integer");
            }
            hasDigit = true;
            value = Math.addExact(Math.multiplyExact(value, 10), next - '0');
        }
        return null;
    }

    private record LineInt(int value, int nextIndex) {}
}
