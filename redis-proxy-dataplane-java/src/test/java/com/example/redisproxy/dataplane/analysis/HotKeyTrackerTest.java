package com.example.redisproxy.dataplane.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.redisproxy.dataplane.protocol.RespRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class HotKeyTrackerTest {
    @Test
    void returnsTopKeysByCount() {
        HotKeyTracker tracker = new HotKeyTracker(new SimpleMeterRegistry());
        tracker.setClock(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));

        tracker.observe("app-a", request("GET", "key-1"));
        tracker.observe("app-a", request("GET", "key-1"));
        tracker.observe("app-a", request("GET", "key-2"));

        assertThat(tracker.snapshot(2))
                .extracting(HotKeyTracker.Entry::key, HotKeyTracker.Entry::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("key-1", 2L),
                        org.assertj.core.groups.Tuple.tuple("key-2", 1L));
    }

    @Test
    void recordsMetricsAndDropsWhenCapacityIsFull() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HotKeyTracker tracker = new HotKeyTracker(registry);
        tracker.setMaxTracked(1);
        tracker.setClock(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));

        tracker.observe("app-a", request("GET", "key-1"));
        tracker.observe("app-a", request("GET", "key-2"));

        assertThat(registry.get("redis.proxy.hot.key.observed").tag("namespace", "app-a").tag("command", "GET").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("redis.proxy.hot.key.dropped").tag("namespace", "app-a").tag("command", "GET").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("redis.proxy.hot.key.tracked.keys").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("redis.proxy.hot.key.topk.count").tag("namespace", "app-a").tag("command", "GET").tag("key", "key-1").tag("rank", "1").gauge().value()).isEqualTo(1.0);
    }

    private static RespRequest request(String... args) {
        return new RespRequest(
                Unpooled.EMPTY_BUFFER,
                Arrays.stream(args).map(arg -> arg.getBytes(StandardCharsets.US_ASCII)).toList());
    }
}
