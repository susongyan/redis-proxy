package com.example.redisproxy.dataplane.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class NamespaceLimiterTest {
    @Test
    void enforcesConnectionLimit() {
        NamespaceLimiter limiter = new NamespaceLimiter(new SimpleMeterRegistry());
        ProxyProperties.Namespace namespace = namespace("app-a");
        namespace.getLimits().setMaxConnections(1);

        assertThat(limiter.bind("", namespace).allowed()).isTrue();
        assertThat(limiter.bind("", namespace).allowed()).isFalse();
        limiter.unbind("app-a");
        assertThat(limiter.bind("", namespace).allowed()).isTrue();
    }

    @Test
    void enforcesQpsLimit() {
        NamespaceLimiter limiter = new NamespaceLimiter(new SimpleMeterRegistry());
        limiter.setClock(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
        ProxyProperties.Namespace namespace = namespace("app-a");
        namespace.getLimits().setMaxQps(1);

        assertThat(limiter.allowRequest(namespace).allowed()).isTrue();
        limiter.finishRequest("app-a");
        assertThat(limiter.allowRequest(namespace).allowed()).isFalse();

        limiter.setClock(Clock.fixed(Instant.ofEpochMilli(2_000), ZoneOffset.UTC));
        assertThat(limiter.allowRequest(namespace).allowed()).isTrue();
    }

    @Test
    void enforcesInflightLimit() {
        NamespaceLimiter limiter = new NamespaceLimiter(new SimpleMeterRegistry());
        ProxyProperties.Namespace namespace = namespace("app-a");
        namespace.getLimits().setMaxInflight(1);

        assertThat(limiter.allowRequest(namespace).allowed()).isTrue();
        assertThat(limiter.allowRequest(namespace).allowed()).isFalse();
        limiter.finishRequest("app-a");
        assertThat(limiter.allowRequest(namespace).allowed()).isTrue();
    }

    @Test
    void recordsLimitConfigMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NamespaceLimiter limiter = new NamespaceLimiter(registry);
        ProxyProperties.Namespace namespace = namespace("app-a");
        namespace.getLimits().setMaxConnections(2);
        namespace.getLimits().setMaxQps(3);
        namespace.getLimits().setMaxInflight(4);

        assertThat(limiter.bind("", namespace).allowed()).isTrue();

        assertThat(registry.get("redis.proxy.namespace.limit.config").tag("namespace", "app-a").tag("limit", "connections").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("redis.proxy.namespace.limit.config").tag("namespace", "app-a").tag("limit", "qps").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("redis.proxy.namespace.limit.config").tag("namespace", "app-a").tag("limit", "inflight").gauge().value()).isEqualTo(4.0);
    }

    private static ProxyProperties.Namespace namespace(String name) {
        ProxyProperties.Namespace namespace = new ProxyProperties.Namespace();
        namespace.setName(name);
        return namespace;
    }
}
