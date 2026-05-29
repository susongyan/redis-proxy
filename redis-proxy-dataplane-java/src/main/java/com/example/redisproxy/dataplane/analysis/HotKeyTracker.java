package com.example.redisproxy.dataplane.analysis;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.governance.GovernancePolicy;
import com.example.redisproxy.dataplane.protocol.ArgRef;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HotKeyTracker {
    private static final int DEFAULT_MAX_TRACKED = 10_000;
    private static final int DEFAULT_METRICS_TOP_N = 20;
    private static final long DEFAULT_WINDOW_MILLIS = 60_000;
    private static final long DEFAULT_BUCKET_MILLIS = 1_000;

    private final MeterRegistry registry;
    private final Map<KeyId, Window> counts = new ConcurrentHashMap<>();
    private final AtomicReference<List<Meter>> topMeters = new AtomicReference<>(List.of());
    private final AtomicInteger trackedKeys = new AtomicInteger();
    private final AtomicLong lastRefreshMillis = new AtomicLong();
    private final AtomicBoolean refreshScheduled = new AtomicBoolean();
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hot-key-topk-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private Clock clock = Clock.systemUTC();
    private volatile TrackerConfig config = new TrackerConfig(true, DEFAULT_MAX_TRACKED, DEFAULT_METRICS_TOP_N, DEFAULT_BUCKET_MILLIS, (int) (DEFAULT_WINDOW_MILLIS / DEFAULT_BUCKET_MILLIS));

    @Autowired
    public HotKeyTracker(MeterRegistry registry, ProxyProperties properties) {
        this.registry = registry;
        Gauge.builder("redis.proxy.hot.key.tracked.keys", trackedKeys, AtomicInteger::get).register(registry);
        configure(properties.getAnalysis().getHotKey());
    }

    HotKeyTracker(MeterRegistry registry) {
        this(registry, new ProxyProperties());
    }

    public void configure(ProxyProperties.HotKey next) {
        long nextWindowMillis = Math.max(1, next.getWindowSeconds()) * 1000L;
        long nextBucketMillis = Math.max(1, next.getBucketMillis());
        int nextBucketCount = Math.max(1, (int) (nextWindowMillis / nextBucketMillis));
        int nextMaxTracked = Math.max(1, next.getMaxTrackedKeys());
        int nextMetricsTopN = Math.max(1, next.getMetricsTopN());
        TrackerConfig previous = this.config;
        TrackerConfig updated = new TrackerConfig(next.isEnabled(), nextMaxTracked, nextMetricsTopN, nextBucketMillis, nextBucketCount);
        this.config = updated;
        if (previous.bucketMillis() != nextBucketMillis || previous.bucketCount() != nextBucketCount || previous.maxTracked() != nextMaxTracked || !updated.enabled()) {
            clear();
        }
    }

    public void observe(String namespace, RespRequest request) {
        TrackerConfig cfg = config;
        if (!cfg.enabled()) {
            return;
        }
        GovernancePolicy.KeyResult keys = GovernancePolicy.keys(request);
        if (!keys.supported() || keys.keys().isEmpty()) {
            return;
        }
        String command = request.command();
        long nowMillis = clock.millis();
        for (ArgRef raw : keys.keys()) {
            KeyId key = new KeyId(namespace, command, raw.utf8());
            Window window = windowFor(key, cfg, nowMillis, namespace, command);
            if (window == null) {
                continue;
            }
            window.increment(nowMillis);
            registry.counter("redis.proxy.hot.key.observed", "namespace", namespace, "command", command).increment();
        }
        scheduleRefresh(nowMillis);
    }

    public List<Entry> snapshot(int limit) {
        TrackerConfig cfg = config;
        if (limit <= 0) {
            limit = cfg.metricsTopN();
        }
        if (!cfg.enabled()) {
            return List.of();
        }
        prune(clock.millis(), cfg);
        trackedKeys.set(counts.size());
        return top(limit, cfg);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    void setMaxTracked(int maxTracked) {
        TrackerConfig previous = config;
        config = new TrackerConfig(previous.enabled(), maxTracked, previous.metricsTopN(), previous.bucketMillis(), previous.bucketCount());
    }

    void setWindow(long windowMillis, long bucketMillis) {
        TrackerConfig previous = config;
        config = new TrackerConfig(previous.enabled(), previous.maxTracked(), previous.metricsTopN(), bucketMillis, Math.max(1, (int) (windowMillis / bucketMillis)));
        counts.clear();
        trackedKeys.set(0);
    }

    @PreDestroy
    public void stop() {
        refreshExecutor.shutdownNow();
    }

    private Window windowFor(KeyId key, TrackerConfig cfg, long nowMillis, String namespace, String command) {
        Window existing = counts.get(key);
        if (existing != null && existing.matches(cfg)) {
            return existing;
        }
        if (existing != null) {
            Window replacement = new Window(cfg, nowMillis);
            return replaceExistingWindow(key, existing, replacement, cfg, nowMillis, namespace, command);
        }
        if (!reserve(namespace, command, cfg.maxTracked())) {
            return null;
        }
        Window created = new Window(cfg, nowMillis);
        Window raced = counts.putIfAbsent(key, created);
        if (raced != null) {
            trackedKeys.decrementAndGet();
            return raced.matches(cfg) ? raced : windowFor(key, cfg, nowMillis, namespace, command);
        }
        return created;
    }

    private Window replaceExistingWindow(KeyId key, Window expected, Window replacement, TrackerConfig cfg,
                                         long nowMillis, String namespace, String command) {
        if (counts.replace(key, expected, replacement)) {
            return replacement;
        }
        Window current = counts.get(key);
        if (current == null) {
            return windowFor(key, cfg, nowMillis, namespace, command);
        }
        if (current.matches(cfg)) {
            return current;
        }
        return replaceExistingWindow(key, current, new Window(cfg, nowMillis), cfg, nowMillis, namespace, command);
    }

    private boolean reserve(String namespace, String command, int maxTracked) {
        while (true) {
            int current = trackedKeys.get();
            if (current >= maxTracked) {
                registry.counter("redis.proxy.hot.key.dropped", "namespace", namespace, "command", command).increment();
                return false;
            }
            if (trackedKeys.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void scheduleRefresh(long nowMillis) {
        long previous = lastRefreshMillis.get();
        if (previous != 0 && nowMillis - previous < 1000) {
            return;
        }
        if (!lastRefreshMillis.compareAndSet(previous, nowMillis) || !refreshScheduled.compareAndSet(false, true)) {
            return;
        }
        refreshExecutor.execute(() -> {
            try {
                refreshMetrics();
            } finally {
                refreshScheduled.set(false);
            }
        });
    }

    synchronized void refreshMetrics() {
        TrackerConfig cfg = config;
        List<Entry> top = top(cfg.metricsTopN(), cfg);
        for (Meter meter : topMeters.getAndSet(List.of())) {
            registry.remove(meter);
        }
        List<Meter> nextMeters = new java.util.ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) {
            Entry entry = top.get(i);
            AtomicInteger value = new AtomicInteger((int) Math.min(Integer.MAX_VALUE, entry.count()));
            Meter meter = Gauge.builder("redis.proxy.hot.key.topk.count", value, AtomicInteger::get)
                    .tag("namespace", entry.namespace())
                    .tag("command", entry.command())
                    .tag("key", entry.key())
                    .tag("rank", String.valueOf(i + 1))
                    .strongReference(true)
                    .register(registry);
            nextMeters.add(meter);
        }
        for (Meter meter : topMeters.getAndSet(List.copyOf(nextMeters))) {
            registry.remove(meter);
        }
    }

    private void clear() {
        for (Meter meter : topMeters.getAndSet(List.of())) {
            registry.remove(meter);
        }
        counts.clear();
        trackedKeys.set(0);
    }

    private List<Entry> top(int limit, TrackerConfig cfg) {
        long nowMillis = clock.millis();
        return counts.entrySet().stream()
                .map(item -> new Entry(item.getKey().namespace(), item.getKey().command(), item.getKey().key(), item.getValue().total(nowMillis)))
                .filter(entry -> entry.count() > 0)
                .sorted(Comparator.comparingLong(Entry::count).reversed()
                        .thenComparing(Entry::namespace)
                        .thenComparing(Entry::command)
                        .thenComparing(Entry::key))
                .limit(limit)
                .toList();
    }

    private void prune(long nowMillis, TrackerConfig cfg) {
        counts.entrySet().removeIf(item -> {
            boolean remove = !item.getValue().matches(cfg) || item.getValue().total(nowMillis) == 0;
            if (remove) {
                trackedKeys.updateAndGet(value -> Math.max(0, value - 1));
            }
            return remove;
        });
    }

    private record KeyId(String namespace, String command, String key) {
    }

    public record Entry(String namespace, String command, String key, long count) {
    }

    private record TrackerConfig(boolean enabled, int maxTracked, int metricsTopN, long bucketMillis, int bucketCount) {
    }

    private static final class Window {
        private static final long EXPIRED_RELATIVE_INDEX = -1L;
        private static final int COUNT_BITS = 32;
        private static final long COUNT_MASK = 0xffff_ffffL;
        private final long bucketMillis;
        private final int bucketCount;
        private final long baseBucketIndex;
        private final AtomicLongArray buckets;

        private Window(TrackerConfig cfg, long nowMillis) {
            this.bucketMillis = cfg.bucketMillis();
            this.bucketCount = cfg.bucketCount();
            this.baseBucketIndex = Math.floorDiv(nowMillis, bucketMillis);
            this.buckets = new AtomicLongArray(bucketCount);
            long empty = pack(EXPIRED_RELATIVE_INDEX, 0);
            for (int i = 0; i < bucketCount; i++) {
                buckets.set(i, empty);
            }
        }

        private boolean matches(TrackerConfig cfg) {
            return bucketMillis == cfg.bucketMillis() && bucketCount == cfg.bucketCount();
        }

        private void increment(long nowMillis) {
            long currentRelative = relativeBucket(nowMillis);
            if (currentRelative < 0 || currentRelative > Integer.MAX_VALUE) {
                return;
            }
            int slot = Math.floorMod(currentRelative, bucketCount);
            while (true) {
                long before = buckets.get(slot);
                long beforeRelative = relativeIndex(before);
                int beforeCount = count(before);
                long next = beforeRelative == currentRelative
                        ? pack(currentRelative, beforeCount + 1)
                        : pack(currentRelative, 1);
                if (buckets.compareAndSet(slot, before, next)) {
                    return;
                }
                Thread.onSpinWait();
            }
        }

        private long total(long nowMillis) {
            long currentRelative = relativeBucket(nowMillis);
            long total = 0;
            for (int i = 0; i < bucketCount; i++) {
                long state = buckets.get(i);
                long relative = relativeIndex(state);
                if (relative >= 0 && currentRelative >= relative && currentRelative - relative < bucketCount) {
                    total += count(state);
                }
            }
            return total;
        }

        private long relativeBucket(long nowMillis) {
            return Math.floorDiv(nowMillis, bucketMillis) - baseBucketIndex;
        }

        private static long pack(long relativeIndex, int count) {
            return (relativeIndex << COUNT_BITS) | (count & COUNT_MASK);
        }

        private static long relativeIndex(long state) {
            return state >> COUNT_BITS;
        }

        private static int count(long state) {
            return (int) (state & COUNT_MASK);
        }
    }
}
