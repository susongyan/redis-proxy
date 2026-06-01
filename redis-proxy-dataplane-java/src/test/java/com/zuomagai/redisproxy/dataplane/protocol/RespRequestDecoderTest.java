package com.zuomagai.redisproxy.dataplane.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RespRequestDecoderTest {
    @Test
    void decodesGetWithoutMaterializingArguments() {
        EmbeddedChannel channel = new EmbeddedChannel(new RespRequestDecoder(1024));
        channel.writeInbound(resp("GET", "foo"));

        RespRequest request = channel.readInbound();
        try {
            assertThat(request.command()).isEqualTo("GET");
            assertThat(request.argCount()).isEqualTo(2);
            assertThat(request.argUtf8(1)).isEqualTo("foo");
            assertThat(request.arg(1).raw()).isSameAs(request.raw());
            assertThat(request.raw().toString(StandardCharsets.US_ASCII)).isEqualTo("*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n");
        } finally {
            request.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void decodesMgetMsetAndLargeBulkString() {
        EmbeddedChannel channel = new EmbeddedChannel(new RespRequestDecoder(2048));
        String large = "x".repeat(512);
        channel.writeInbound(resp("MGET", "a", "b"));
        channel.writeInbound(resp("MSET", "a", "1", "b", large));

        RespRequest mget = channel.readInbound();
        RespRequest mset = channel.readInbound();
        try {
            assertThat(mget.command()).isEqualTo("MGET");
            assertThat(mget.argUtf8(2)).isEqualTo("b");
            assertThat(mset.command()).isEqualTo("MSET");
            assertThat(mset.argUtf8(4)).isEqualTo(large);
        } finally {
            mget.release();
            mset.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void waitsForPartialFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new RespRequestDecoder(1024));
        channel.writeInbound(Unpooled.copiedBuffer("*2\r\n$3\r\nGET\r\n$3\r\nfo", StandardCharsets.US_ASCII));
        Object empty = channel.readInbound();
        assertThat(empty).isNull();

        channel.writeInbound(Unpooled.copiedBuffer("o\r\n", StandardCharsets.US_ASCII));
        RespRequest request = channel.readInbound();
        try {
            assertThat(request.argUtf8(1)).isEqualTo("foo");
        } finally {
            request.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsInvalidFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new RespRequestDecoder(1024));
        channel.writeInbound(Unpooled.copiedBuffer("*1\r\n$X\r\nGET\r\n", StandardCharsets.US_ASCII));

        ByteBuf response = channel.readOutbound();
        try {
            assertThat(response.toString(StandardCharsets.US_ASCII)).isEqualTo("-ERR invalid RESP frame\r\n");
        } finally {
            response.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsOversizedFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new RespRequestDecoder(16));
        channel.writeInbound(resp("GET", "foo"));

        ByteBuf response = channel.readOutbound();
        try {
            assertThat(response.toString(StandardCharsets.US_ASCII)).isEqualTo("-ERR request frame exceeds 16\r\n");
            Object empty = channel.readInbound();
            assertThat(empty).isNull();
        } finally {
            response.release();
            channel.finishAndReleaseAll();
        }
    }

    private static ByteBuf resp(String... args) {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeCharSequence("*" + args.length + "\r\n", StandardCharsets.US_ASCII);
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            buffer.writeCharSequence("$" + bytes.length + "\r\n", StandardCharsets.US_ASCII);
            buffer.writeBytes(bytes);
            buffer.writeCharSequence("\r\n", StandardCharsets.US_ASCII);
        }
        return buffer;
    }
}
