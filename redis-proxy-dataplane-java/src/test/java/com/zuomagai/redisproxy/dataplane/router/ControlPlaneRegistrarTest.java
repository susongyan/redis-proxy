package com.zuomagai.redisproxy.dataplane.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zuomagai.redisproxy.dataplane.config.ProxyProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ControlPlaneRegistrarTest {
    @Test
    void registrationEndpointAcceptsConfigAndApiBaseUrls() {
        assertThat(ControlPlaneRegistrar.registrationEndpoint("http://127.0.0.1:8090/api/v1/config"))
                .isEqualTo("http://127.0.0.1:8090/api/v1/observability/targets");
        assertThat(ControlPlaneRegistrar.registrationEndpoint("http://127.0.0.1:8090/api/v1"))
                .isEqualTo("http://127.0.0.1:8090/api/v1/observability/targets");
    }

    @Test
    void payloadUsesProxyIdentityAndDefaults() {
        ProxyProperties properties = new ProxyProperties();
        properties.getInstance().setProxyId("proxy-java-1");
        properties.getInstance().setGroup("frontend");
        properties.getInstance().setAdvertiseIp("10.0.0.2");
        properties.getInstance().setAdvertisePort(6381);
        properties.getAdmin().setListen("0.0.0.0:18080");
        properties.getRouting().setDefaultCluster("redis-a");
        properties.getRegistration().setHeartbeatIntervalSeconds(10);
        ControlPlaneRegistrar registrar = new ControlPlaneRegistrar(properties, new ObjectMapper(), new SimpleMeterRegistry());

        assertThat(registrar.adminUrl()).isEqualTo("http://127.0.0.1:18080");
        assertThat(registrar.payload())
                .containsEntry("proxyId", "proxy-java-1")
                .containsEntry("group", "frontend")
                .containsEntry("advertiseIp", "10.0.0.2")
                .containsEntry("advertisePort", 6381)
                .containsEntry("adminUrl", "http://127.0.0.1:18080")
                .containsEntry("dataplane", "java")
                .containsEntry("cluster", "redis-a")
                .containsEntry("registrationSource", "dataplane")
                .containsEntry("heartbeatTtlSeconds", 45);
    }
}
