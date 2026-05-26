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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    void concurrentUpdatesKeepMaxBytesAndCount() throws Exception {
        LargeKeyTracker tracker = new LargeKeyTracker(new SimpleMeterRegistry(), properties());
        tracker.setClock(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
        LargeKeyTracker.Context context = tracker.context("app-a", request("GET", "key-1"));

        runConcurrent(64, index -> {
            tracker.observeResponse(context, 128 + index);
            return 1;
        });

        assertThat(tracker.snapshot(1))
                .extracting(LargeKeyTracker.Entry::key, LargeKeyTracker.Entry::count, LargeKeyTracker.Entry::maxResponseBytes)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("key-1", 64L, 191));
    }

    @Test
    void capacityReservationPreventsConcurrentOverTracking() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProxyProperties properties = properties();
        properties.getAnalysis().getLargeKey().setMaxTrackedKeys(4);
        LargeKeyTracker tracker = new LargeKeyTracker(registry, properties);
        tracker.setClock(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));

        runConcurrent(32, index -> {
            tracker.observeResponse(tracker.context("app-a", request("GET", "key-" + index)), 128);
            return 1;
        });

        assertThat(registry.get("redis.proxy.large.key.tracked.keys").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("redis.proxy.large.key.dropped").tag("namespace", "app-a").tag("command", "GET").counter().count()).isEqualTo(28.0);
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

    private static void runConcurrent(int tasks, IndexedCallable task) throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                int index = i;
                calls.add(() -> task.call(index));
            }
            for (Future<Integer> future : executor.invokeAll(calls)) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IndexedCallable {
        Integer call(int index) throws Exception;
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
