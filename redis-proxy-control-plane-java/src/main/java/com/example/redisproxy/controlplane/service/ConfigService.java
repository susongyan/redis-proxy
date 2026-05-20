package com.example.redisproxy.controlplane.service;

import com.example.redisproxy.controlplane.model.ProxyConfig;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class ConfigService {
    private final AtomicReference<ProxyConfig> current = new AtomicReference<>(defaultConfig());
    private final CopyOnWriteArrayList<Watcher> watchers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService watcherTimeouts = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "config-watch-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public ProxyConfig get() {
        return current.get();
    }

    public ProxyConfig update(ProxyConfig config) {
        validateSemantics(config);
        current.set(config);
        completeMatchingWatchers(config);
        return config;
    }

    public CompletableFuture<Optional<ProxyConfig>> watch(long routeEpoch, Duration timeout) {
        ProxyConfig config = current.get();
        if (config.getRouting().getRouteEpoch() > routeEpoch) {
            return CompletableFuture.completedFuture(Optional.of(config));
        }
        CompletableFuture<Optional<ProxyConfig>> future = new CompletableFuture<>();
        Watcher watcher = new Watcher(routeEpoch, future);
        watchers.add(watcher);
        watcherTimeouts.schedule(() -> {
            if (future.complete(Optional.empty())) {
                watchers.remove(watcher);
            }
        }, Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);

        ProxyConfig latest = current.get();
        if (latest.getRouting().getRouteEpoch() > routeEpoch && future.complete(Optional.of(latest))) {
            watchers.remove(watcher);
        }
        return future;
    }

    private void completeMatchingWatchers(ProxyConfig config) {
        for (Watcher watcher : watchers) {
            if (config.getRouting().getRouteEpoch() > watcher.routeEpoch()
                    && watcher.future().complete(Optional.of(config))) {
                watchers.remove(watcher);
            }
        }
    }

    @PreDestroy
    public void stop() {
        watcherTimeouts.shutdownNow();
    }

    private static void validateSemantics(ProxyConfig config) {
        List<String> clusterNames = config.getBackends().getClusters().stream()
                .map(ProxyConfig.Cluster::getName)
                .toList();
        if (!clusterNames.contains(config.getRouting().getDefaultCluster())) {
            throw new IllegalArgumentException("routing.defaultCluster does not exist in backends.clusters");
        }
        if (!List.of("standalone", "cluster").contains(config.getMode())) {
            throw new IllegalArgumentException("mode must be standalone or cluster");
        }
        if (config.getRouting().getRouteEpoch() < 0) {
            throw new IllegalArgumentException("routing.routeEpoch must be >= 0");
        }
        if (config.getRouting().getClusterSlotsRefreshIntervalSeconds() < 0) {
            throw new IllegalArgumentException("routing.clusterSlotsRefreshIntervalSeconds must be >= 0");
        }
        for (ProxyConfig.RouteRule rule : config.getRouting().getRules()) {
            if (!clusterNames.contains(rule.getCluster())) {
                throw new IllegalArgumentException("routing rule " + rule.getName() + " references unknown cluster");
            }
            if (rule.getTrafficPercent() < 0 || rule.getTrafficPercent() > 100) {
                throw new IllegalArgumentException("routing rule " + rule.getName() + " trafficPercent must be between 0 and 100");
            }
            boolean hasKeyPrefix = rule.getKeyPrefix() != null && !rule.getKeyPrefix().isBlank();
            boolean hasHashTag = rule.getHashTag() != null && !rule.getHashTag().isBlank();
            if (!hasKeyPrefix && !hasHashTag) {
                throw new IllegalArgumentException("routing rule " + rule.getName() + " must set keyPrefix or hashTag");
            }
        }
    }

    private static ProxyConfig defaultConfig() {
        ProxyConfig config = new ProxyConfig();
        ProxyConfig.Cluster cluster = new ProxyConfig.Cluster();
        cluster.setName("redis-a");
        cluster.setNodes(List.of("127.0.0.1:7000", "127.0.0.1:7001", "127.0.0.1:7002"));
        config.getBackends().setClusters(List.of(cluster));
        config.getRouting().setDefaultCluster("redis-a");
        return config;
    }

    private record Watcher(long routeEpoch, CompletableFuture<Optional<ProxyConfig>> future) {
    }
}
