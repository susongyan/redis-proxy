package com.zuomagai.redisproxy.dataplane.router;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RedisSlotTest {
    @Test
    void matchesRedisClusterSlotExamples() {
        assertThat(RedisSlot.slot("123456789".getBytes(StandardCharsets.US_ASCII))).isEqualTo(12739);
        assertThat(RedisSlot.slot("foo".getBytes(StandardCharsets.US_ASCII))).isEqualTo(12182);
        assertThat(RedisSlot.slot("{user}:1".getBytes(StandardCharsets.US_ASCII))).isEqualTo(5474);
        assertThat(RedisSlot.slot("{user}:2".getBytes(StandardCharsets.US_ASCII))).isEqualTo(5474);
    }

    @Test
    void computesSlotFromByteBufWithoutCopying() {
        ByteBuf key = Unpooled.copiedBuffer("xx{user}:1yy", StandardCharsets.US_ASCII);
        try {
            assertThat(RedisSlot.slot(key, 2, "{user}:1".length())).isEqualTo(5474);
        } finally {
            key.release();
        }
    }
}
