package com.example.redisproxy.dataplane.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.ArrayList;
import java.util.List;

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
            Line arrayLine = readLine(in, cursor);
            if (arrayLine == null) {
                return;
            }
            int count = Integer.parseInt(arrayLine.value());
            cursor = arrayLine.nextIndex();
            List<byte[]> args = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (cursor >= in.writerIndex() || in.getByte(cursor) != '$') {
                    return;
                }
                Line bulkLine = readLine(in, cursor + 1);
                if (bulkLine == null) {
                    return;
                }
                int size = Integer.parseInt(bulkLine.value());
                cursor = bulkLine.nextIndex();
                if (in.writerIndex() < cursor + size + 2) {
                    return;
                }
                byte[] arg = new byte[size];
                in.getBytes(cursor, arg);
                args.add(arg);
                cursor += size + 2;
            }
            int len = cursor - start;
            if (len > maxRequestBytes) {
                ctx.writeAndFlush(Unpooled.copiedBuffer(("-ERR request frame exceeds " + maxRequestBytes + "\r\n").getBytes()));
                in.readerIndex(cursor);
                return;
            }
            ByteBuf raw = in.retainedSlice(start, len);
            in.readerIndex(cursor);
            out.add(new RespRequest(raw, args));
        } catch (RuntimeException e) {
            in.readerIndex(in.writerIndex());
            ctx.writeAndFlush(Unpooled.copiedBuffer("-ERR invalid RESP frame\r\n".getBytes()));
        }
    }

    private static Line readLine(ByteBuf in, int index) {
        for (int i = index; i + 1 < in.writerIndex(); i++) {
            if (in.getByte(i) == '\r' && in.getByte(i + 1) == '\n') {
                return new Line(in.toString(index, i - index, java.nio.charset.StandardCharsets.US_ASCII), i + 2);
            }
        }
        return null;
    }

    private record Line(String value, int nextIndex) {}
}
