package com.zuomagai.redisproxy.dataplane.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.zuomagai.redisproxy.dataplane.config.ProxyProperties;
import com.zuomagai.redisproxy.dataplane.protocol.RespRequest;
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
        tracker.refreshMetrics();
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

    @Test
    void appliesRuntimeConfigurationAndCanDisableObservation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HotKeyTracker tracker = new HotKeyTracker(registry);
        ProxyProperties.HotKey hotKey = new ProxyProperties.HotKey();
        hotKey.setEnabled(false);
        hotKey.setWindowSeconds(60);
        hotKey.setBucketMillis(1000);
        hotKey.setMaxTrackedKeys(100);
        hotKey.setMetricsTopN(10);
        tracker.configure(hotKey);

        tracker.observe("app-a", request("GET", "key-1"));

        assertThat(tracker.snapshot(10)).isEmpty();
    }

    @Test
    void refreshMetricsPrunesExpiredKeysAndFreesCapacity() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HotKeyTracker tracker = new HotKeyTracker(registry);
        tracker.setMaxTracked(1);
        tracker.setWindow(60_000, 1_000);
        ClockHolder clock = new ClockHolder(1_000);
        tracker.setClock(clock);

        tracker.observe("app-a", request("GET", "key-1"));
        assertThat(tracker.refreshMetrics()).isTrue();
        assertThat(registry.get("redis.proxy.hot.key.topk.count").tag("key", "key-1").gauge().value()).isEqualTo(1.0);

        clock.setMillis(62_000);
        assertThat(tracker.refreshMetrics()).isFalse();
        assertThat(registry.get("redis.proxy.hot.key.tracked.keys").gauge().value()).isEqualTo(0.0);
        assertThat(registry.find("redis.proxy.hot.key.topk.count").gauge()).isNull();

        tracker.observe("app-a", request("GET", "key-2"));
        assertThat(tracker.snapshot(10))
                .extracting(HotKeyTracker.Entry::key)
                .containsExactly("key-2");
        assertThat(registry.find("redis.proxy.hot.key.dropped").counter()).isNull();
    }

    @Test
    void observeAfterStopDoesNotThrow() {
        HotKeyTracker tracker = new HotKeyTracker(new SimpleMeterRegistry());
        tracker.setClock(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
        tracker.stop();

        tracker.observe("app-a", request("GET", "key-1"));

        assertThat(tracker.snapshot(1))
                .extracting(HotKeyTracker.Entry::key, HotKeyTracker.Entry::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("key-1", 1L));
    }

    @Test
    void observesSameKeyConcurrentlyWithoutLosingCounts() throws Exception {
        HotKeyTracker tracker = new HotKeyTracker(new SimpleMeterRegistry());
        tracker.setClock(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));

        runConcurrent(128, () -> {
            tracker.observe("app-a", request("GET", "hot"));
            return 1;
        });

        assertThat(tracker.snapshot(1))
                .extracting(HotKeyTracker.Entry::key, HotKeyTracker.Entry::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("hot", 128L));
    }

    @Test
    void capacityReservationPreventsConcurrentOverTracking() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HotKeyTracker tracker = new HotKeyTracker(registry);
        tracker.setMaxTracked(4);
        tracker.setClock(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));

        runConcurrent(32, index -> {
            tracker.observe("app-a", request("GET", "key-" + index));
            return 1;
        });

        assertThat(registry.get("redis.proxy.hot.key.tracked.keys").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("redis.proxy.hot.key.dropped").tag("namespace", "app-a").tag("command", "GET").counter().count()).isEqualTo(28.0);
    }

    private static RespRequest request(String... args) {
        return new RespRequest(
                Unpooled.EMPTY_BUFFER,
                Arrays.stream(args).map(arg -> arg.getBytes(StandardCharsets.US_ASCII)).toList());
    }

    private static void runConcurrent(int tasks, Callable<Integer> task) throws Exception {
        runConcurrent(tasks, ignored -> task.call());
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
