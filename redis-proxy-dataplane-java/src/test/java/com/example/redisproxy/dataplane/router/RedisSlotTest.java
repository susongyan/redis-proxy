package com.example.redisproxy.dataplane.router;

import static org.assertj.core.api.Assertions.assertThat;

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
}
