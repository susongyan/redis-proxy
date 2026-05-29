package com.example.redisproxy.dataplane.netty;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class PipelineStats {
    private final MeterRegistry registry;
    private final AtomicInteger bufferedResponses = new AtomicInteger();
    private final AtomicLong flushes = new AtomicLong();
    private final AtomicLong lastFlushBatchSize = new AtomicLong();
    private final AtomicLong maxFlushBatchSize = new AtomicLong();
    private final AtomicLong holBlocked = new AtomicLong();
    private final AtomicLong holMaxWaitMillis = new AtomicLong();

    public PipelineStats(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("redis.proxy.pipeline.buffered.responses", bufferedResponses);
        registry.gauge("redis.proxy.pipeline.hol.max.wait.millis", holMaxWaitMillis);
    }

    public void observeBuffered(int size) {
        bufferedResponses.set(Math.max(0, size));
    }

    public void observeFlushBatch(int size) {
        if (size <= 0) {
            return;
        }
        flushes.incrementAndGet();
        lastFlushBatchSize.set(size);
        maxFlushBatchSize.accumulateAndGet(size, Math::max);
        registry.summary("redis.proxy.pipeline.flush.batch.size").record(size);
    }

    public void observeHolBlocked(String reason, int pendingSize) {
        holBlocked.incrementAndGet();
        observeBuffered(pendingSize);
        registry.counter("redis.proxy.pipeline.hol.blocked", "reason", reason == null || reason.isBlank() ? "backend_pending" : reason).increment();
    }

    public void observeHolWaitMillis(long waitMillis) {
        if (waitMillis <= 0) {
            return;
        }
        holMaxWaitMillis.accumulateAndGet(waitMillis, Math::max);
    }

    public Map<String, Object> snapshot() {
        return Map.of(
                "bufferedResponses", bufferedResponses.get(),
                "flushes", flushes.get(),
                "lastFlushBatchSize", lastFlushBatchSize.get(),
                "maxFlushBatchSize", maxFlushBatchSize.get(),
                "holBlocked", holBlocked.get(),
                "holMaxWaitMillis", holMaxWaitMillis.get());
    }
}
