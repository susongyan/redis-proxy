package com.example.redisproxy.controlplane.service;

import com.example.redisproxy.controlplane.model.ProxyConfig;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class ConfigService {
    private final AtomicReference<ProxyConfig> current = new AtomicReference<>(defaultConfig());

    public ProxyConfig get() {
        return current.get();
    }

    public ProxyConfig update(ProxyConfig config) {
        validateSemantics(config);
        current.set(config);
        return config;
    }

    private static void validateSemantics(ProxyConfig config) {
        boolean found = config.getBackends().getClusters().stream()
                .anyMatch(cluster -> cluster.getName().equals(config.getRouting().getDefaultCluster()));
        if (!found) {
            throw new IllegalArgumentException("routing.defaultCluster does not exist in backends.clusters");
        }
        if (!List.of("standalone", "cluster").contains(config.getMode())) {
            throw new IllegalArgumentException("mode must be standalone or cluster");
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
}
