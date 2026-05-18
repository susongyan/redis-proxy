package com.example.redisproxy.dataplane.protocol;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.List;

public record RespRequest(ByteBuf raw, List<byte[]> args) {
    public String command() {
        if (args.isEmpty()) {
            return "";
        }
        return new String(args.getFirst(), StandardCharsets.US_ASCII).toUpperCase();
    }

    public void release() {
        raw.release();
    }
}
