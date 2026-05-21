package com.example.redisproxy.dataplane.governance;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class NamespaceLimiter {
    private final MeterRegistry registry;
    private final Map<String, Integer> connections = new HashMap<>();
    private final Map<String, Integer> inflight = new HashMap<>();
    private final Map<String, QpsWindow> qpsWindows = new HashMap<>();
    private final Map<String, AtomicInteger> connectionGauges = new HashMap<>();
    private final Map<String, AtomicInteger> inflightGauges = new HashMap<>();
    private Clock clock = Clock.systemUTC();

    public NamespaceLimiter(MeterRegistry registry) {
        this.registry = registry;
    }

    public synchronized LimitResult bind(String current, ProxyProperties.Namespace next) {
        if (next == null || next.getName() == null || next.getName().isBlank() || next.getName().equals(current)) {
            return LimitResult.allow();
        }
        String namespace = next.getName();
        if (next.getLimits().getMaxConnections() > 0 && connections.getOrDefault(namespace, 0) >= next.getLimits().getMaxConnections()) {
            return LimitResult.rejected("connection_limit");
        }
        if (current != null && !current.isBlank() && connections.getOrDefault(current, 0) > 0) {
            connections.put(current, connections.get(current) - 1);
            gauge(connectionGauges, "redis.proxy.namespace.connections", current).set(connections.get(current));
        }
        connections.put(namespace, connections.getOrDefault(namespace, 0) + 1);
        gauge(connectionGauges, "redis.proxy.namespace.connections", namespace).set(connections.get(namespace));
        return LimitResult.allow();
    }

    public synchronized void unbind(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        if (connections.getOrDefault(namespace, 0) > 0) {
            connections.put(namespace, connections.get(namespace) - 1);
        }
        gauge(connectionGauges, "redis.proxy.namespace.connections", namespace).set(connections.getOrDefault(namespace, 0));
    }

    public synchronized LimitResult allowRequest(ProxyProperties.Namespace namespace) {
        if (namespace == null || namespace.getName() == null || namespace.getName().isBlank()) {
            return LimitResult.allow();
        }
        String name = namespace.getName();
        ProxyProperties.NamespaceLimits limits = namespace.getLimits();
        long second = clock.millis() / 1000;
        if (limits.getMaxQps() > 0) {
            QpsWindow window = qpsWindows.get(name);
            if (window == null || window.second != second) {
                window = new QpsWindow(second, 0);
            }
            if (window.count >= limits.getMaxQps()) {
                qpsWindows.put(name, window);
                return LimitResult.rejected("qps_limit");
            }
            qpsWindows.put(name, new QpsWindow(second, window.count + 1));
        }
        if (limits.getMaxInflight() > 0 && inflight.getOrDefault(name, 0) >= limits.getMaxInflight()) {
            return LimitResult.rejected("inflight_limit");
        }
        inflight.put(name, inflight.getOrDefault(name, 0) + 1);
        gauge(inflightGauges, "redis.proxy.namespace.inflight", name).set(inflight.get(name));
        return LimitResult.allow();
    }

    public synchronized void finishRequest(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        if (inflight.getOrDefault(namespace, 0) > 0) {
            inflight.put(namespace, inflight.get(namespace) - 1);
        }
        gauge(inflightGauges, "redis.proxy.namespace.inflight", namespace).set(inflight.getOrDefault(namespace, 0));
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private AtomicInteger gauge(Map<String, AtomicInteger> gauges, String name, String namespace) {
        return gauges.computeIfAbsent(namespace, key ->
                registry.gauge(name, List.of(Tag.of("namespace", key)), new AtomicInteger()));
    }

    public record LimitResult(boolean allowed, String reason) {
        static LimitResult allow() {
            return new LimitResult(true, "");
        }

        static LimitResult rejected(String reason) {
            return new LimitResult(false, reason);
        }
    }

    private record QpsWindow(long second, int count) {
    }
}
