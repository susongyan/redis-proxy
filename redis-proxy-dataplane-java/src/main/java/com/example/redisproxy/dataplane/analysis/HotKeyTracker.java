package com.example.redisproxy.dataplane.analysis;

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
import org.springframework.stereotype.Component;

@Component
public class HotKeyTracker {
    private static final int DEFAULT_MAX_TRACKED = 10_000;
    private static final int DEFAULT_METRICS_TOP_N = 20;

    private final MeterRegistry registry;
    private final Map<KeyId, Long> counts = new HashMap<>();
    private final List<Meter> topMeters = new ArrayList<>();
    private final AtomicInteger trackedKeys = new AtomicInteger();
    private Clock clock = Clock.systemUTC();
    private long lastRefreshMillis;
    private int maxTracked = DEFAULT_MAX_TRACKED;

    public HotKeyTracker(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("redis.proxy.hot.key.tracked.keys", trackedKeys, AtomicInteger::get).register(registry);
    }

    public synchronized void observe(String namespace, RespRequest request) {
        GovernancePolicy.KeyResult keys = GovernancePolicy.keys(request);
        if (!keys.supported() || keys.keys().isEmpty()) {
            return;
        }
        String command = request.command();
        for (byte[] raw : keys.keys()) {
            KeyId key = new KeyId(namespace, command, new String(raw, StandardCharsets.UTF_8));
            if (!counts.containsKey(key) && counts.size() >= maxTracked) {
                registry.counter("redis.proxy.hot.key.dropped", "namespace", namespace, "command", command).increment();
                continue;
            }
            counts.put(key, counts.getOrDefault(key, 0L) + 1);
            registry.counter("redis.proxy.hot.key.observed", "namespace", namespace, "command", command).increment();
        }
        trackedKeys.set(counts.size());
        long now = clock.millis();
        if (lastRefreshMillis == 0 || now - lastRefreshMillis >= 1000) {
            refreshMetrics();
            lastRefreshMillis = now;
        }
    }

    public synchronized List<Entry> snapshot(int limit) {
        if (limit <= 0) {
            limit = DEFAULT_METRICS_TOP_N;
        }
        return top(limit);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    void setMaxTracked(int maxTracked) {
        this.maxTracked = maxTracked;
    }

    private void refreshMetrics() {
        for (Meter meter : topMeters) {
            registry.remove(meter);
        }
        topMeters.clear();
        List<Entry> top = top(DEFAULT_METRICS_TOP_N);
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

    private List<Entry> top(int limit) {
        return counts.entrySet().stream()
                .map(item -> new Entry(item.getKey().namespace(), item.getKey().command(), item.getKey().key(), item.getValue()))
                .sorted(Comparator.comparingLong(Entry::count).reversed()
                        .thenComparing(Entry::namespace)
                        .thenComparing(Entry::command)
                        .thenComparing(Entry::key))
                .limit(limit)
                .toList();
    }

    private record KeyId(String namespace, String command, String key) {
    }

    public record Entry(String namespace, String command, String key, long count) {
    }
}
