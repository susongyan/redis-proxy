package com.example.redisproxy.dataplane.analysis;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.governance.GovernancePolicy;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
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
public class HotKeyTracker {
    private static final int DEFAULT_MAX_TRACKED = 10_000;
    private static final int DEFAULT_METRICS_TOP_N = 20;
    private static final long DEFAULT_WINDOW_MILLIS = 60_000;
    private static final long DEFAULT_BUCKET_MILLIS = 1_000;

    private final MeterRegistry registry;
    private final Map<KeyId, Window> counts = new HashMap<>();
    private final List<Meter> topMeters = new ArrayList<>();
    private final AtomicInteger trackedKeys = new AtomicInteger();
    private Clock clock = Clock.systemUTC();
    private long lastRefreshMillis;
    private boolean enabled = true;
    private int maxTracked = DEFAULT_MAX_TRACKED;
    private int metricsTopN = DEFAULT_METRICS_TOP_N;
    private long bucketMillis = DEFAULT_BUCKET_MILLIS;
    private int bucketCount = (int) (DEFAULT_WINDOW_MILLIS / DEFAULT_BUCKET_MILLIS);

    @Autowired
    public HotKeyTracker(MeterRegistry registry, ProxyProperties properties) {
        this.registry = registry;
        Gauge.builder("redis.proxy.hot.key.tracked.keys", trackedKeys, AtomicInteger::get).register(registry);
        configure(properties.getAnalysis().getHotKey());
    }

    HotKeyTracker(MeterRegistry registry) {
        this(registry, new ProxyProperties());
    }

    public synchronized void configure(ProxyProperties.HotKey config) {
        long nextWindowMillis = Math.max(1, config.getWindowSeconds()) * 1000L;
        long nextBucketMillis = Math.max(1, config.getBucketMillis());
        int nextBucketCount = Math.max(1, (int) (nextWindowMillis / nextBucketMillis));
        int nextMaxTracked = Math.max(1, config.getMaxTrackedKeys());
        int nextMetricsTopN = Math.max(1, config.getMetricsTopN());
        boolean changedWindow = this.bucketMillis != nextBucketMillis || this.bucketCount != nextBucketCount;
        boolean changedCapacity = this.maxTracked != nextMaxTracked;
        this.enabled = config.isEnabled();
        this.bucketMillis = nextBucketMillis;
        this.bucketCount = nextBucketCount;
        this.maxTracked = nextMaxTracked;
        this.metricsTopN = nextMetricsTopN;
        if (changedWindow || changedCapacity || !enabled) {
            clear();
        }
    }

    public synchronized void observe(String namespace, RespRequest request) {
        if (!enabled) {
            return;
        }
        GovernancePolicy.KeyResult keys = GovernancePolicy.keys(request);
        if (!keys.supported() || keys.keys().isEmpty()) {
            return;
        }
        String command = request.command();
        long nowMillis = clock.millis();
        long currentBucket = nowMillis / bucketMillis;
        for (byte[] raw : keys.keys()) {
            KeyId key = new KeyId(namespace, command, new String(raw, StandardCharsets.UTF_8));
            Window window = counts.get(key);
            if (window == null && counts.size() >= maxTracked) {
                registry.counter("redis.proxy.hot.key.dropped", "namespace", namespace, "command", command).increment();
                continue;
            }
            if (window == null) {
                window = new Window(bucketCount);
                counts.put(key, window);
            }
            window.increment(currentBucket);
            registry.counter("redis.proxy.hot.key.observed", "namespace", namespace, "command", command).increment();
        }
        prune(currentBucket);
        trackedKeys.set(counts.size());
        if (lastRefreshMillis == 0 || nowMillis - lastRefreshMillis >= 1000) {
            refreshMetrics();
            lastRefreshMillis = nowMillis;
        }
    }

    public synchronized List<Entry> snapshot(int limit) {
        if (limit <= 0) {
            limit = metricsTopN;
        }
        if (!enabled) {
            return List.of();
        }
        prune(clock.millis() / bucketMillis);
        trackedKeys.set(counts.size());
        return top(limit);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    void setMaxTracked(int maxTracked) {
        this.maxTracked = maxTracked;
    }

    void setWindow(long windowMillis, long bucketMillis) {
        this.bucketMillis = bucketMillis;
        this.bucketCount = Math.max(1, (int) (windowMillis / bucketMillis));
        this.counts.clear();
        this.trackedKeys.set(0);
    }

    private void refreshMetrics() {
        for (Meter meter : topMeters) {
            registry.remove(meter);
        }
        topMeters.clear();
        List<Entry> top = top(metricsTopN);
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
            topMeters.add(meter);
        }
    }

    private void clear() {
        for (Meter meter : topMeters) {
            registry.remove(meter);
        }
        topMeters.clear();
        counts.clear();
        trackedKeys.set(0);
    }

    private List<Entry> top(int limit) {
        long currentBucket = clock.millis() / bucketMillis;
        return counts.entrySet().stream()
                .map(item -> new Entry(item.getKey().namespace(), item.getKey().command(), item.getKey().key(), item.getValue().total(currentBucket, bucketCount)))
                .filter(entry -> entry.count() > 0)
                .sorted(Comparator.comparingLong(Entry::count).reversed()
                        .thenComparing(Entry::namespace)
                        .thenComparing(Entry::command)
                        .thenComparing(Entry::key))
                .limit(limit)
                .toList();
    }

    private void prune(long currentBucket) {
        counts.entrySet().removeIf(item -> item.getValue().total(currentBucket, bucketCount) == 0);
    }

    private record KeyId(String namespace, String command, String key) {
    }

    public record Entry(String namespace, String command, String key, long count) {
    }

    private static final class Window {
        private final Bucket[] buckets;

        private Window(int bucketCount) {
            this.buckets = new Bucket[bucketCount];
            for (int i = 0; i < bucketCount; i++) {
                this.buckets[i] = new Bucket(-1, 0);
            }
        }

        private void increment(long bucket) {
            int slot = (int) (bucket % buckets.length);
            if (buckets[slot].index != bucket) {
                buckets[slot] = new Bucket(bucket, 0);
            }
            buckets[slot].count++;
        }

        private long total(long currentBucket, int bucketCount) {
            long total = 0;
            for (Bucket bucket : buckets) {
                if (bucket.index >= 0 && currentBucket - bucket.index < bucketCount) {
                    total += bucket.count;
                }
            }
            return total;
        }
    }

    private static final class Bucket {
        private final long index;
        private long count;

        private Bucket(long index, long count) {
            this.index = index;
            this.count = count;
        }
    }
}
