package com.example.redisproxy.dataplane.analysis;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.governance.GovernancePolicy;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SlowQueryTracker {
    private static final long DEFAULT_WINDOW_MILLIS = 300_000;
    private static final long DEFAULT_BUCKET_MILLIS = 1_000;

    private final MeterRegistry registry;
    private final Map<KeyId, Window> counts = new HashMap<>();
    private final AtomicInteger trackedKeys = new AtomicInteger();
    private final AtomicInteger endToEndThresholdGauge = new AtomicInteger();
    private final AtomicInteger backendThresholdGauge = new AtomicInteger();
    private Clock clock = Clock.systemUTC();
    private boolean enabled = true;
    private int endToEndThresholdMillis = 100;
    private int backendThresholdMillis = 50;
    private int maxTracked = 10_000;
    private int debugTopN = 100;
    private long bucketMillis = DEFAULT_BUCKET_MILLIS;
    private int bucketCount = (int) (DEFAULT_WINDOW_MILLIS / DEFAULT_BUCKET_MILLIS);

    @Autowired
    public SlowQueryTracker(MeterRegistry registry, ProxyProperties properties) {
        this.registry = registry;
        Gauge.builder("redis.proxy.slow.query.tracked.keys", trackedKeys, AtomicInteger::get).register(registry);
        Gauge.builder("redis.proxy.slow.query.end.to.end.threshold.millis", endToEndThresholdGauge, AtomicInteger::get).register(registry);
        Gauge.builder("redis.proxy.slow.query.backend.threshold.millis", backendThresholdGauge, AtomicInteger::get).register(registry);
        configure(properties.getAnalysis().getSlowQuery());
    }

    SlowQueryTracker(MeterRegistry registry) {
        this(registry, new ProxyProperties());
    }

    public synchronized void configure(ProxyProperties.SlowQuery config) {
        long windowMillis = Math.max(1, config.getWindowSeconds()) * 1000L;
        long nextBucketMillis = Math.max(1, config.getBucketMillis());
        int nextBucketCount = Math.max(1, (int) (windowMillis / nextBucketMillis));
        int nextMaxTracked = Math.max(1, config.getMaxTrackedKeys());
        boolean changedWindow = bucketMillis != nextBucketMillis || bucketCount != nextBucketCount;
        boolean changedCapacity = maxTracked != nextMaxTracked;
        enabled = config.isEnabled();
        endToEndThresholdMillis = Math.max(0, config.getEndToEndThresholdMillis());
        backendThresholdMillis = Math.max(0, config.getBackendThresholdMillis());
        endToEndThresholdGauge.set(endToEndThresholdMillis);
        backendThresholdGauge.set(backendThresholdMillis);
        bucketMillis = nextBucketMillis;
        bucketCount = nextBucketCount;
        maxTracked = nextMaxTracked;
        debugTopN = Math.max(1, config.getDebugTopN());
        if (changedWindow || changedCapacity || !enabled) {
            counts.clear();
            trackedKeys.set(0);
        }
    }

    public Context context(String namespace, RespRequest request) {
        GovernancePolicy.KeyResult result = GovernancePolicy.keys(request);
        List<String> keys = new ArrayList<>();
        if (result.supported()) {
            for (byte[] key : result.keys()) {
                keys.add(new String(key, StandardCharsets.UTF_8));
            }
        }
        return new Context(namespace, request.command(), keys, result.supported());
    }

    public synchronized void observe(Context context, long endToEndMillis, long backendMillis) {
        if (context == null || !enabled) {
            return;
        }
        boolean endToEndHit = endToEndThresholdMillis > 0 && endToEndMillis >= endToEndThresholdMillis;
        boolean backendHit = backendThresholdMillis > 0 && backendMillis >= backendThresholdMillis;
        if (!endToEndHit && !backendHit) {
            return;
        }
        if (!context.supported() || context.keys().isEmpty()) {
            registry.counter("redis.proxy.slow.query.unsupported", "command", context.command()).increment();
            return;
        }
        long nowMillis = clock.millis();
        long currentBucket = nowMillis / bucketMillis;
        String trigger = trigger(endToEndHit, backendHit);
        for (String raw : context.keys()) {
            KeyId key = new KeyId(context.namespace(), context.command(), raw);
            Window window = counts.get(key);
            if (window == null && counts.size() >= maxTracked) {
                registry.counter("redis.proxy.slow.query.dropped", "namespace", context.namespace(), "command", context.command()).increment();
                continue;
            }
            if (window == null) {
                window = new Window(bucketCount);
                counts.put(key, window);
            }
            window.observe(currentBucket, endToEndMillis, backendMillis, nowMillis / 1000);
            registry.counter("redis.proxy.slow.query.observed", "namespace", context.namespace(), "command", context.command(), "trigger", trigger).increment();
        }
        prune(currentBucket);
        trackedKeys.set(counts.size());
    }

    public synchronized List<Entry> snapshot(int limit) {
        if (limit <= 0) {
            limit = debugTopN;
        }
        if (!enabled) {
            return List.of();
        }
        long currentBucket = clock.millis() / bucketMillis;
        prune(currentBucket);
        trackedKeys.set(counts.size());
        return top(currentBucket, limit);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private List<Entry> top(long currentBucket, int limit) {
        return counts.entrySet().stream()
                .map(item -> item.getValue().total(item.getKey(), currentBucket, bucketCount))
                .filter(entry -> entry.count() > 0)
                .sorted(Comparator.comparingLong((Entry entry) -> Math.max(entry.maxEndToEndMillis(), entry.maxBackendMillis())).reversed()
                        .thenComparing(Entry::count, Comparator.reverseOrder())
                        .thenComparing(Entry::namespace)
                        .thenComparing(Entry::command)
                        .thenComparing(Entry::key))
                .limit(limit)
                .toList();
    }

    private void prune(long currentBucket) {
        counts.entrySet().removeIf(item -> item.getValue().total(item.getKey(), currentBucket, bucketCount).count() == 0);
    }

    private static String trigger(boolean endToEndHit, boolean backendHit) {
        if (endToEndHit && backendHit) {
            return "both";
        }
        return endToEndHit ? "end_to_end" : "backend";
    }

    public record Context(String namespace, String command, List<String> keys, boolean supported) {}
    public record Entry(String namespace, String command, String key, long count, long maxEndToEndMillis, long maxBackendMillis, long lastSeenUnix) {}
    private record KeyId(String namespace, String command, String key) {}

    private static final class Window {
        private final Bucket[] buckets;

        private Window(int bucketCount) {
            this.buckets = new Bucket[bucketCount];
            for (int i = 0; i < bucketCount; i++) {
                this.buckets[i] = new Bucket(-1);
            }
        }

        private void observe(long bucket, long endToEndMillis, long backendMillis, long lastSeenUnix) {
            int slot = (int) (bucket % buckets.length);
            if (buckets[slot].index != bucket) {
                buckets[slot] = new Bucket(bucket);
            }
            buckets[slot].count++;
            buckets[slot].maxEndToEndMillis = Math.max(buckets[slot].maxEndToEndMillis, endToEndMillis);
            buckets[slot].maxBackendMillis = Math.max(buckets[slot].maxBackendMillis, backendMillis);
            buckets[slot].lastSeenUnix = Math.max(buckets[slot].lastSeenUnix, lastSeenUnix);
        }

        private Entry total(KeyId key, long currentBucket, int bucketCount) {
            long count = 0;
            long maxEndToEndMillis = 0;
            long maxBackendMillis = 0;
            long lastSeenUnix = 0;
            for (Bucket bucket : buckets) {
                if (bucket.index >= 0 && currentBucket - bucket.index < bucketCount) {
                    count += bucket.count;
                    maxEndToEndMillis = Math.max(maxEndToEndMillis, bucket.maxEndToEndMillis);
                    maxBackendMillis = Math.max(maxBackendMillis, bucket.maxBackendMillis);
                    lastSeenUnix = Math.max(lastSeenUnix, bucket.lastSeenUnix);
                }
            }
            return new Entry(key.namespace(), key.command(), key.key(), count, maxEndToEndMillis, maxBackendMillis, lastSeenUnix);
        }
    }

    private static final class Bucket {
        private final long index;
        private long count;
        private long maxEndToEndMillis;
        private long maxBackendMillis;
        private long lastSeenUnix;

        private Bucket(long index) {
            this.index = index;
        }
    }
}
