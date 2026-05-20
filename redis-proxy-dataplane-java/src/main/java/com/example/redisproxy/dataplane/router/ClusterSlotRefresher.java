package com.example.redisproxy.dataplane.router;

import com.example.redisproxy.dataplane.backend.BackendPool;
import com.example.redisproxy.dataplane.config.ProxyProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ClusterSlotRefresher {
    private static final Logger log = LoggerFactory.getLogger(ClusterSlotRefresher.class);

    private final ProxyProperties properties;
    private final RouteResolver routeResolver;
    private final BackendPool backendPool;
    private final MeterRegistry registry;
    private ScheduledExecutorService scheduler;
    private final AtomicLong lastForcedRefreshMillis = new AtomicLong();

    public ClusterSlotRefresher(ProxyProperties properties, RouteResolver routeResolver, BackendPool backendPool, MeterRegistry registry) {
        this.properties = properties;
        this.routeResolver = routeResolver;
        this.backendPool = backendPool;
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        refreshOnce();
        int intervalSeconds = properties.getRouting().getClusterSlotsRefreshIntervalSeconds();
        if (!"cluster".equals(properties.getMode()) || intervalSeconds <= 0) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "cluster-slot-refresher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.schedule(this::scheduledRefresh, intervalSeconds, TimeUnit.SECONDS);
    }

    public void triggerMovedRefresh() {
        if (!"cluster".equals(properties.getMode()) || scheduler == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastForcedRefreshMillis.get();
        if (now - last < 2000 || !lastForcedRefreshMillis.compareAndSet(last, now)) {
            return;
        }
        scheduler.execute(this::refreshOnce);
    }

    private void scheduledRefresh() {
        try {
            refreshOnce();
        } finally {
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.schedule(this::scheduledRefresh, nextIntervalSeconds(), TimeUnit.SECONDS);
            }
        }
    }

    void refreshOnce() {
        try {
            routeResolver.refreshSlots(backendPool, Duration.ofSeconds(3));
            if ("cluster".equals(properties.getMode())) {
                registry.counter("redis.proxy.cluster.slot.refresh", "result", "success").increment();
            }
        } catch (Exception e) {
            if ("cluster".equals(properties.getMode())) {
                registry.counter("redis.proxy.cluster.slot.refresh", "result", "error").increment();
            }
            log.warn("refresh cluster slots failed", e);
        }
    }

    private int nextIntervalSeconds() {
        if (degraded()) {
            return 5;
        }
        return properties.getRouting().getClusterSlotsRefreshIntervalSeconds();
    }

    private boolean degraded() {
        if (!"cluster".equals(properties.getMode())) {
            return false;
        }
        for (String clusterName : routeResolver.routeClusters()) {
            if (routeResolver.clusterSlotCoverage(clusterName) != 16384) {
                return true;
            }
            for (String owner : routeResolver.clusterSlotOwners(clusterName)) {
                if (!backendPool.hasActive(owner)) {
                    return true;
                }
            }
        }
        return false;
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
