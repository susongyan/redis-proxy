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

    @Test
    void expiresOldCountsOutsideSlidingWindow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HotKeyTracker tracker = new HotKeyTracker(registry);
        tracker.setWindow(60_000, 1_000);
        ClockHolder clock = new ClockHolder(0);
        tracker.setClock(clock);

        tracker.observe("app-a", request("GET", "key-1"));
        clock.setMillis(59_000);
        tracker.observe("app-a", request("GET", "key-2"));

        assertThat(tracker.snapshot(10)).hasSize(2);

        clock.setMillis(61_000);
        assertThat(tracker.snapshot(10))
                .extracting(HotKeyTracker.Entry::key, HotKeyTracker.Entry::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("key-2", 1L));
        assertThat(registry.get("redis.proxy.hot.key.tracked.keys").gauge().value()).isEqualTo(1.0);
    }

    private static RespRequest request(String... args) {
        return new RespRequest(
                Unpooled.EMPTY_BUFFER,
                Arrays.stream(args).map(arg -> arg.getBytes(StandardCharsets.US_ASCII)).toList());
    }

    private static final class ClockHolder extends Clock {
        private long millis;

        private ClockHolder(long millis) {
            this.millis = millis;
        }

        private void setMillis(long millis) {
            this.millis = millis;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
