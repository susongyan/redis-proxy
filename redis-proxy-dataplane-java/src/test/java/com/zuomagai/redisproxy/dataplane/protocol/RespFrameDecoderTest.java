package com.zuomagai.redisproxy.dataplane.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RespFrameDecoderTest {
    @Test
    void decodesCommonRespFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(new RespFrameDecoder(1024));
        channel.writeInbound(Unpooled.copiedBuffer(
                "+OK\r\n-ERR nope\r\n:1\r\n$3\r\nfoo\r\n*2\r\n$3\r\nbar\r\n:2\r\n",
                StandardCharsets.US_ASCII));

        assertFrame(channel, "+OK\r\n");
        assertFrame(channel, "-ERR nope\r\n");
        assertFrame(channel, ":1\r\n");
        assertFrame(channel, "$3\r\nfoo\r\n");
        assertFrame(channel, "*2\r\n$3\r\nbar\r\n:2\r\n");
        Object empty = channel.readInbound();
        assertThat(empty).isNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void waitsForCompleteFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new RespFrameDecoder(1024));
        channel.writeInbound(Unpooled.copiedBuffer("$5\r\nhe", StandardCharsets.US_ASCII));
        Object empty = channel.readInbound();
        assertThat(empty).isNull();

        channel.writeInbound(Unpooled.copiedBuffer("llo\r\n", StandardCharsets.US_ASCII));
        assertFrame(channel, "$5\r\nhello\r\n");
        channel.finishAndReleaseAll();
    }

    private static void assertFrame(EmbeddedChannel channel, String expected) {
        ByteBuf frame = channel.readInbound();
        try {
            assertThat(frame.toString(StandardCharsets.US_ASCII)).isEqualTo(expected);
        } finally {
            frame.release();
        }
    }
}
