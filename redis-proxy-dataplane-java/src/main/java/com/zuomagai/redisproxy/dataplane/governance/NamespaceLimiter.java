package com.zuomagai.redisproxy.dataplane.governance;

import com.zuomagai.redisproxy.dataplane.config.ProxyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class NamespaceLimiter {
    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> connections = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inflight = new ConcurrentHashMap<>();
    private final Map<String, AtomicSlidingWindow> qpsWindows = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> connectionGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inflightGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> limitConfigGauges = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public NamespaceLimiter(MeterRegistry registry) {
        this.registry = registry;
    }

    public LimitResult bind(String current, ProxyProperties.Namespace next) {
        if (next == null || next.getName() == null || next.getName().isBlank() || next.getName().equals(current)) {
            return LimitResult.allow();
        }
        String namespace = next.getName();
        observeLimits(next);
        AtomicInteger counter = counter(connections, namespace);
        if (!tryAcquire(counter, next.getLimits().getMaxConnections())) {
            return LimitResult.rejected("connection_limit");
        }
        AtomicInteger previous = current == null || current.isBlank() ? null : connections.get(current);
        if (previous != null && previous.get() > 0) {
            int value = decrement(previous);
            gauge(connectionGauges, "redis.proxy.namespace.connections", current).set(value);
        }
        gauge(connectionGauges, "redis.proxy.namespace.connections", namespace).set(counter.get());
        return LimitResult.allow();
    }

    public void unbind(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        AtomicInteger counter = connections.get(namespace);
        if (counter != null) {
            gauge(connectionGauges, "redis.proxy.namespace.connections", namespace).set(decrement(counter));
        } else {
            gauge(connectionGauges, "redis.proxy.namespace.connections", namespace).set(0);
        }
    }

    public LimitResult allowRequest(ProxyProperties.Namespace namespace) {
        if (namespace == null || namespace.getName() == null || namespace.getName().isBlank()) {
            return LimitResult.allow();
        }
        String name = namespace.getName();
        ProxyProperties.NamespaceLimits limits = namespace.getLimits();
        observeLimits(namespace);
        if (limits.getMaxQps() > 0) {
            long nowMillis = clock.millis();
            AtomicSlidingWindow window = qpsWindow(name, nowMillis);
            if (!window.allow(nowMillis, limits.getMaxQps()).allowed()) {
                return LimitResult.rejected("qps_limit");
            }
        }
        AtomicInteger inflightCounter = counter(inflight, name);
        if (!tryAcquire(inflightCounter, limits.getMaxInflight())) {
            return LimitResult.rejected("inflight_limit");
        }
        gauge(inflightGauges, "redis.proxy.namespace.inflight", name).set(inflightCounter.get());
        return LimitResult.allow();
    }

    public void finishRequest(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        AtomicInteger counter = inflight.get(namespace);
        if (counter != null) {
            gauge(inflightGauges, "redis.proxy.namespace.inflight", namespace).set(decrement(counter));
        } else {
            gauge(inflightGauges, "redis.proxy.namespace.inflight", namespace).set(0);
        }
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private AtomicInteger gauge(Map<String, AtomicInteger> gauges, String name, String namespace) {
        return gauges.computeIfAbsent(namespace, key ->
                registry.gauge(name, List.of(Tag.of("namespace", key)), new AtomicInteger()));
    }

    private AtomicInteger counter(Map<String, AtomicInteger> counters, String namespace) {
        return counters.computeIfAbsent(namespace, ignored -> new AtomicInteger());
    }

    private AtomicSlidingWindow qpsWindow(String namespace, long nowMillis) {
        AtomicSlidingWindow existing = qpsWindows.get(namespace);
        if (existing != null && existing.canRepresent(nowMillis)) {
            return existing;
        }
        AtomicSlidingWindow created = new AtomicSlidingWindow(1000, 1, nowMillis);
        if (existing == null) {
            AtomicSlidingWindow previous = qpsWindows.putIfAbsent(namespace, created);
            if (previous == null || !previous.canRepresent(nowMillis)) {
                return previous == null ? created : replaceWindow(namespace, previous, created, nowMillis);
            }
            return previous;
        }
        return replaceWindow(namespace, existing, created, nowMillis);
    }

    private AtomicSlidingWindow replaceWindow(String namespace, AtomicSlidingWindow expected,
                                             AtomicSlidingWindow replacement, long nowMillis) {
        if (qpsWindows.replace(namespace, expected, replacement)) {
            return replacement;
        }
        AtomicSlidingWindow current = qpsWindows.get(namespace);
        if (current != null && current.canRepresent(nowMillis)) {
            return current;
        }
        AtomicSlidingWindow previous = qpsWindows.putIfAbsent(namespace, replacement);
        return previous == null || !previous.canRepresent(nowMillis) ? replacement : previous;
    }

    private static boolean tryAcquire(AtomicInteger counter, int limit) {
        while (true) {
            int current = counter.get();
            if (limit > 0 && current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private static int decrement(AtomicInteger counter) {
        return counter.updateAndGet(value -> Math.max(0, value - 1));
    }

    private void observeLimits(ProxyProperties.Namespace namespace) {
        setLimitConfig(namespace.getName(), "connections", namespace.getLimits().getMaxConnections());
        setLimitConfig(namespace.getName(), "qps", namespace.getLimits().getMaxQps());
        setLimitConfig(namespace.getName(), "inflight", namespace.getLimits().getMaxInflight());
    }

    private void setLimitConfig(String namespace, String limit, int value) {
        String key = namespace + "\u0000" + limit;
        limitConfigGauges.computeIfAbsent(key, ignored ->
                registry.gauge("redis.proxy.namespace.limit.config", List.of(Tag.of("namespace", namespace), Tag.of("limit", limit)), new AtomicInteger()))
                .set(value);
    }

    public record LimitResult(boolean allowed, String reason) {
        static LimitResult allow() {
            return new LimitResult(true, "");
        }

        static LimitResult rejected(String reason) {
            return new LimitResult(false, reason);
        }
    }

}
