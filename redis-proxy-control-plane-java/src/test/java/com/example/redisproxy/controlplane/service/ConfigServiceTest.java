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
}
