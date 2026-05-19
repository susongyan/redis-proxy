package com.example.redisproxy.dataplane.router;

import com.example.redisproxy.dataplane.backend.BackendPool;
import com.example.redisproxy.dataplane.config.ProxyProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
        scheduler.scheduleWithFixedDelay(this::refreshOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void refreshOnce() {
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

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
