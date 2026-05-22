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

class LargeKeyTrackerTest {
    @Test
    void recordsTopKeysAndMultiKeyCommands() {
        LargeKeyTracker tracker = new LargeKeyTracker(new SimpleMeterRegistry(), properties());
        tracker.setClock(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
        LargeKeyTracker.Context context = tracker.context("app-a", request("MGET", "key-1", "key-2"));

        tracker.observeRequest(context, 32);
        tracker.observeResponse(context, 128);

        assertThat(tracker.snapshot(10))
                .extracting(LargeKeyTracker.Entry::key, LargeKeyTracker.Entry::count, LargeKeyTracker.Entry::maxRequestBytes, LargeKeyTracker.Entry::maxResponseBytes)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("key-1", 2L, 32, 128),
                        org.assertj.core.groups.Tuple.tuple("key-2", 2L, 32, 128));
    }

    @Test
    void recordsDroppedUnsupportedAndDisable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProxyProperties properties = properties();
        properties.getAnalysis().getLargeKey().setMaxTrackedKeys(1);
        LargeKeyTracker tracker = new LargeKeyTracker(registry, properties);

        tracker.observeResponse(tracker.context("app-a", request("GET", "key-1")), 128);
        tracker.observeResponse(tracker.context("app-a", request("GET", "key-2")), 128);
        tracker.observeResponse(tracker.context("app-a", request("SCAN", "0")), 128);

        assertThat(registry.get("redis.proxy.large.key.dropped").tag("namespace", "app-a").tag("command", "GET").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("redis.proxy.large.key.unsupported").tag("command", "SCAN").tag("direction", "response").counter().count()).isEqualTo(1.0);

        properties.getAnalysis().getLargeKey().setEnabled(false);
        tracker.configure(properties.getAnalysis().getLargeKey());
        assertThat(tracker.snapshot(10)).isEmpty();
    }

    @Test
    void expiresOutsideSlidingWindow() {
        LargeKeyTracker tracker = new LargeKeyTracker(new SimpleMeterRegistry(), properties());
        ClockHolder clock = new ClockHolder(0);
        tracker.setClock(clock);

        tracker.observeResponse(tracker.context("app-a", request("GET", "key-1")), 128);
        clock.setMillis(301_000);

        assertThat(tracker.snapshot(10)).isEmpty();
    }

    private static ProxyProperties properties() {
        ProxyProperties properties = new ProxyProperties();
        properties.getAnalysis().getLargeKey().setRequestBytesThreshold(1);
        properties.getAnalysis().getLargeKey().setResponseBytesThreshold(1);
        properties.getAnalysis().getLargeKey().setWindowSeconds(300);
        properties.getAnalysis().getLargeKey().setBucketMillis(1000);
        properties.getAnalysis().getLargeKey().setMaxTrackedKeys(10);
        properties.getAnalysis().getLargeKey().setDebugTopN(10);
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
