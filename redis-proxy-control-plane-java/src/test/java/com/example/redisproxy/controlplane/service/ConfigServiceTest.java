package com.example.redisproxy.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.redisproxy.controlplane.model.ProxyConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigServiceTest {
    @Test
    void rejectsMissingDefaultCluster() {
        ConfigService service = new ConfigService();
        ProxyConfig config = new ProxyConfig();
        ProxyConfig.Cluster cluster = new ProxyConfig.Cluster();
        cluster.setName("redis-a");
        cluster.setNodes(List.of("127.0.0.1:6379"));
        config.getBackends().setClusters(List.of(cluster));
        config.getRouting().setDefaultCluster("missing");

        assertThatThrownBy(() -> service.update(config))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storesValidConfig() {
        ConfigService service = new ConfigService();
        ProxyConfig config = service.get();
        assertThat(service.update(config)).isSameAs(config);
    }

    @Test
    void rejectsRouteRuleUnknownCluster() {
        ConfigService service = new ConfigService();
        ProxyConfig config = service.get();
        ProxyConfig.RouteRule rule = new ProxyConfig.RouteRule();
        rule.setName("bad");
        rule.setCluster("missing");
        rule.setKeyPrefix("user:");
        rule.setTrafficPercent(10);
        config.getRouting().setRules(List.of(rule));

        assertThatThrownBy(() -> service.update(config))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsValidRouteRule() {
        ConfigService service = new ConfigService();
        ProxyConfig config = service.get();
        ProxyConfig.Cluster gray = new ProxyConfig.Cluster();
        gray.setName("redis-b");
        gray.setNodes(List.of("127.0.0.1:6380"));
        config.getBackends().setClusters(List.of(config.getBackends().getClusters().getFirst(), gray));
        ProxyConfig.RouteRule rule = new ProxyConfig.RouteRule();
        rule.setName("gray-user");
        rule.setCluster("redis-b");
        rule.setKeyPrefix("user:");
        rule.setTrafficPercent(25);
        config.getRouting().setRules(List.of(rule));

        assertThat(service.update(config)).isSameAs(config);
    }
}
