package com.zuomagai.redisproxy.dataplane.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RespRequest {
    private final ByteBuf raw;
    private final List<ArgRef> args;
    private String command;

    public RespRequest(ByteBuf ignoredRaw, List<byte[]> byteArgs) {
        this(build(byteArgs));
    }

    private RespRequest(ByteBuf raw, List<ArgRef> args, boolean ignored) {
        this.raw = raw;
        this.args = List.copyOf(args);
    }

    private RespRequest(BuiltRequest built) {
        this(built.raw(), built.args(), true);
    }

    public static RespRequest fromArgRefs(ByteBuf raw, List<ArgRef> args) {
        return new RespRequest(raw, args, true);
    }

    public ByteBuf raw() {
        return raw;
    }

    public List<ArgRef> args() {
        return args;
    }

    public int argCount() {
        return args.size();
    }

    public ArgRef arg(int index) {
        return args.get(index);
    }

    public String argUtf8(int index) {
        return arg(index).utf8();
    }

    public byte[] argBytes(int index) {
        return arg(index).copyBytes();
    }

    public String command() {
        if (command == null) {
            command = args.isEmpty() ? "" : args.getFirst().asciiUpper();
        }
        return command;
    }

    public boolean commandEquals(String value) {
        return !args.isEmpty() && args.getFirst().equalsAsciiIgnoreCase(value);
    }

    public void release() {
        raw.release();
    }

    private static BuiltRequest build(List<byte[]> byteArgs) {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeByte('*');
        writeAscii(buffer, Integer.toString(byteArgs.size()));
        buffer.writeBytes(new byte[] {'\r', '\n'});
        List<ArgRef> refs = new ArrayList<>(byteArgs.size());
        for (byte[] arg : byteArgs) {
            buffer.writeByte('$');
            writeAscii(buffer, Integer.toString(arg.length));
            buffer.writeBytes(new byte[] {'\r', '\n'});
            refs.add(new ArgRef(buffer, buffer.writerIndex(), arg.length));
            buffer.writeBytes(arg);
            buffer.writeBytes(new byte[] {'\r', '\n'});
        }
        return new BuiltRequest(buffer, refs);
    }

    private static void writeAscii(ByteBuf buffer, String value) {
        buffer.writeCharSequence(value, StandardCharsets.US_ASCII);
    }

    private record BuiltRequest(ByteBuf raw, List<ArgRef> args) {}
}
