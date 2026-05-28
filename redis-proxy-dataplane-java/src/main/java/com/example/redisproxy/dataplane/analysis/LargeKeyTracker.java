package com.example.redisproxy.dataplane.analysis;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.governance.GovernancePolicy;
import com.example.redisproxy.dataplane.protocol.ArgRef;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LargeKeyTracker {
    private static final long DEFAULT_WINDOW_MILLIS = 300_000;
    private static final long DEFAULT_BUCKET_MILLIS = 1_000;

    private final MeterRegistry registry;
    private final Map<KeyId, Window> counts = new ConcurrentHashMap<>();
    private final AtomicInteger trackedKeys = new AtomicInteger();
    private final AtomicInteger requestThresholdGauge = new AtomicInteger();
    private final AtomicInteger responseThresholdGauge = new AtomicInteger();
    private Clock clock = Clock.systemUTC();
    private volatile TrackerConfig config = new TrackerConfig(true, 0, 0, 10_000, 100, DEFAULT_BUCKET_MILLIS, (int) (DEFAULT_WINDOW_MILLIS / DEFAULT_BUCKET_MILLIS));

    @Autowired
    public LargeKeyTracker(MeterRegistry registry, ProxyProperties properties) {
        this.registry = registry;
        Gauge.builder("redis.proxy.large.key.tracked.keys", trackedKeys, AtomicInteger::get).register(registry);
        Gauge.builder("redis.proxy.large.key.request.threshold.bytes", requestThresholdGauge, AtomicInteger::get).register(registry);
        Gauge.builder("redis.proxy.large.key.response.threshold.bytes", responseThresholdGauge, AtomicInteger::get).register(registry);
        configure(properties.getAnalysis().getLargeKey());
    }

    LargeKeyTracker(MeterRegistry registry) {
        this(registry, new ProxyProperties());
    }

    public void configure(ProxyProperties.LargeKey next) {
        long windowMillis = Math.max(1, next.getWindowSeconds()) * 1000L;
        long nextBucketMillis = Math.max(1, next.getBucketMillis());
        int nextBucketCount = Math.max(1, (int) (windowMillis / nextBucketMillis));
        int nextMaxTracked = Math.max(1, next.getMaxTrackedKeys());
        int requestThreshold = Math.max(0, next.getRequestBytesThreshold());
        int responseThreshold = Math.max(0, next.getResponseBytesThreshold());
        TrackerConfig previous = config;
        TrackerConfig updated = new TrackerConfig(
                next.isEnabled(),
                requestThreshold,
                responseThreshold,
                nextMaxTracked,
                Math.max(1, next.getDebugTopN()),
                nextBucketMillis,
                nextBucketCount);
        config = updated;
        requestThresholdGauge.set(requestThreshold);
        responseThresholdGauge.set(responseThreshold);
        if (previous.bucketMillis() != nextBucketMillis || previous.bucketCount() != nextBucketCount || previous.maxTracked() != nextMaxTracked || !updated.enabled()) {
            counts.clear();
            trackedKeys.set(0);
        }
    }

    public Context context(String namespace, RespRequest request) {
        GovernancePolicy.KeyResult result = GovernancePolicy.keys(request);
        List<String> keys = new ArrayList<>();
        if (result.supported()) {
            for (ArgRef key : result.keys()) {
                keys.add(key.utf8());
            }
        }
        return new Context(namespace, request.command(), keys, result.supported());
    }

    public void observeRequest(Context context, int size) {
        observe(context, size, 0, "request");
    }

    public void observeResponse(Context context, int size) {
        observe(context, 0, size, "response");
    }

    public List<Entry> snapshot(int limit) {
        TrackerConfig cfg = config;
        if (limit <= 0) {
            limit = cfg.debugTopN();
        }
        if (!cfg.enabled()) {
            return List.of();
        }
        long nowMillis = clock.millis();
        prune(nowMillis, cfg);
        trackedKeys.set(counts.size());
        return top(nowMillis, limit);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private void observe(Context context, int requestBytes, int responseBytes, String direction) {
        if (context == null) {
            return;
        }
        TrackerConfig cfg = config;
        if (!cfg.enabled()) {
            return;
        }
        if ("request".equals(direction) && (cfg.requestThreshold() <= 0 || requestBytes < cfg.requestThreshold())) {
            return;
        }
        if ("response".equals(direction) && (cfg.responseThreshold() <= 0 || responseBytes < cfg.responseThreshold())) {
            return;
        }
        if (!context.supported() || context.keys().isEmpty()) {
            registry.counter("redis.proxy.large.key.unsupported", "command", context.command(), "direction", direction).increment();
            return;
        }
        long nowMillis = clock.millis();
        for (String raw : context.keys()) {
            KeyId key = new KeyId(context.namespace(), context.command(), raw);
            Window window = windowFor(key, cfg, nowMillis, context.namespace(), context.command());
            if (window == null) {
                continue;
            }
            window.observe(nowMillis, requestBytes, responseBytes, nowMillis / 1000);
            registry.counter("redis.proxy.large.key.observed", "namespace", context.namespace(), "command", context.command(), "direction", direction).increment();
        }
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
                registry.counter("redis.proxy.large.key.dropped", "namespace", namespace, "command", command).increment();
                return false;
            }
            if (trackedKeys.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private List<Entry> top(long nowMillis, int limit) {
        return counts.entrySet().stream()
                .map(item -> item.getValue().total(item.getKey(), nowMillis))
                .filter(entry -> entry.count() > 0)
                .sorted(Comparator.comparingInt((Entry entry) -> Math.max(entry.maxRequestBytes(), entry.maxResponseBytes())).reversed()
                        .thenComparing(Entry::count, Comparator.reverseOrder())
                        .thenComparing(Entry::namespace)
                        .thenComparing(Entry::command)
                        .thenComparing(Entry::key))
                .limit(limit)
                .toList();
    }

    private void prune(long nowMillis, TrackerConfig cfg) {
        counts.entrySet().removeIf(item -> {
            boolean remove = !item.getValue().matches(cfg) || item.getValue().total(item.getKey(), nowMillis).count() == 0;
            if (remove) {
                trackedKeys.updateAndGet(value -> Math.max(0, value - 1));
            }
            return remove;
        });
    }

    public record Context(String namespace, String command, List<String> keys, boolean supported) {}
    public record Entry(String namespace, String command, String key, long count, int maxRequestBytes, int maxResponseBytes, long lastSeenUnix) {}
    private record KeyId(String namespace, String command, String key) {}
    private record TrackerConfig(boolean enabled, int requestThreshold, int responseThreshold, int maxTracked, int debugTopN, long bucketMillis, int bucketCount) {}

    private static final class Window {
        private final long bucketMillis;
        private final int bucketCount;
        private final long baseBucketIndex;
        private final AtomicReferenceArray<BucketState> buckets;

        private Window(TrackerConfig cfg, long nowMillis) {
            this.bucketMillis = cfg.bucketMillis();
            this.bucketCount = cfg.bucketCount();
            this.baseBucketIndex = Math.floorDiv(nowMillis, bucketMillis);
            this.buckets = new AtomicReferenceArray<>(bucketCount);
            for (int i = 0; i < bucketCount; i++) {
                buckets.set(i, BucketState.empty());
            }
        }

        private boolean matches(TrackerConfig cfg) {
            return bucketMillis == cfg.bucketMillis() && bucketCount == cfg.bucketCount();
        }

        private void observe(long nowMillis, int requestBytes, int responseBytes, long lastSeenUnix) {
            long currentRelative = relativeBucket(nowMillis);
            if (currentRelative < 0 || currentRelative > Integer.MAX_VALUE) {
                return;
            }
            int slot = Math.floorMod(currentRelative, bucketCount);
            while (true) {
                BucketState before = buckets.get(slot);
                BucketState next = before.relativeIndex() == currentRelative
                        ? before.merge(requestBytes, responseBytes, lastSeenUnix)
                        : new BucketState(currentRelative, 1, requestBytes, responseBytes, lastSeenUnix);
                if (buckets.compareAndSet(slot, before, next)) {
                    return;
                }
                Thread.onSpinWait();
            }
        }

        private Entry total(KeyId key, long nowMillis) {
            long currentRelative = relativeBucket(nowMillis);
            long count = 0;
            int maxRequestBytes = 0;
            int maxResponseBytes = 0;
            long lastSeenUnix = 0;
            for (int i = 0; i < bucketCount; i++) {
                BucketState bucket = buckets.get(i);
                if (bucket.relativeIndex() >= 0 && currentRelative >= bucket.relativeIndex() && currentRelative - bucket.relativeIndex() < bucketCount) {
                    count += bucket.count();
                    maxRequestBytes = Math.max(maxRequestBytes, bucket.maxRequestBytes());
                    maxResponseBytes = Math.max(maxResponseBytes, bucket.maxResponseBytes());
                    lastSeenUnix = Math.max(lastSeenUnix, bucket.lastSeenUnix());
                }
            }
            return new Entry(key.namespace(), key.command(), key.key(), count, maxRequestBytes, maxResponseBytes, lastSeenUnix);
        }

        private long relativeBucket(long nowMillis) {
            return Math.floorDiv(nowMillis, bucketMillis) - baseBucketIndex;
        }
    }

    private record BucketState(long relativeIndex, long count, int maxRequestBytes, int maxResponseBytes, long lastSeenUnix) {
        private static BucketState empty() {
            return new BucketState(-1, 0, 0, 0, 0);
        }

        private BucketState merge(int requestBytes, int responseBytes, long lastSeenUnix) {
            return new BucketState(
                    relativeIndex,
                    count + 1,
                    Math.max(maxRequestBytes, requestBytes),
                    Math.max(maxResponseBytes, responseBytes),
                    Math.max(this.lastSeenUnix, lastSeenUnix));
        }
    }
}
