package com.example.redisproxy.dataplane.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SlowQueryTrackerTest {
    @Test
    void recordsTopKeysAndMultiKeyCommands() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SlowQueryTracker tracker = new SlowQueryTracker(registry, properties());
        tracker.setClock(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
        SlowQueryTracker.Context context = tracker.context("app-a", request("MGET", "key-1", "key-2"));

        tracker.observe(context, 20, 8);

        assertThat(tracker.snapshot(10))
                .extracting(SlowQueryTracker.Entry::key, SlowQueryTracker.Entry::count, SlowQueryTracker.Entry::maxEndToEndMillis, SlowQueryTracker.Entry::maxBackendMillis)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("key-1", 1L, 20L, 8L),
                        org.assertj.core.groups.Tuple.tuple("key-2", 1L, 20L, 8L));
        assertThat(registry.get("redis.proxy.slow.query.observed").tag("namespace", "app-a").tag("command", "MGET").tag("trigger", "both").counter().count()).isEqualTo(2.0);
    }

    @Test
    void recordsDroppedUnsupportedAndDisable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProxyProperties properties = properties();
        properties.getAnalysis().getSlowQuery().setMaxTrackedKeys(1);
        SlowQueryTracker tracker = new SlowQueryTracker(registry, properties);

        tracker.observe(tracker.context("app-a", request("GET", "key-1")), 20, 0);
        tracker.observe(tracker.context("app-a", request("GET", "key-2")), 20, 0);
        tracker.observe(tracker.context("app-a", request("SCAN", "0")), 20, 0);

        assertThat(registry.get("redis.proxy.slow.query.dropped").tag("namespace", "app-a").tag("command", "GET").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("redis.proxy.slow.query.unsupported").tag("command", "SCAN").counter().count()).isEqualTo(1.0);

        properties.getAnalysis().getSlowQuery().setEnabled(false);
        tracker.configure(properties.getAnalysis().getSlowQuery());
        assertThat(tracker.snapshot(10)).isEmpty();
    }

    @Test
    void expiresOutsideSlidingWindow() {
        SlowQueryTracker tracker = new SlowQueryTracker(new SimpleMeterRegistry(), properties());
        ClockHolder clock = new ClockHolder(0);
        tracker.setClock(clock);

        tracker.observe(tracker.context("app-a", request("GET", "key-1")), 20, 0);
        clock.setMillis(1_200);

        assertThat(tracker.snapshot(10)).isEmpty();
    }

    private static ProxyProperties properties() {
        ProxyProperties properties = new ProxyProperties();
        properties.getAnalysis().getSlowQuery().setEndToEndThresholdMillis(10);
        properties.getAnalysis().getSlowQuery().setBackendThresholdMillis(5);
        properties.getAnalysis().getSlowQuery().setWindowSeconds(1);
        properties.getAnalysis().getSlowQuery().setBucketMillis(100);
        properties.getAnalysis().getSlowQuery().setMaxTrackedKeys(10);
        properties.getAnalysis().getSlowQuery().setDebugTopN(10);
        return properties;
    }

    private static RespRequest request(String... args) {
        return new RespRequest(
                Unpooled.copiedBuffer("raw", StandardCharsets.US_ASCII),
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
