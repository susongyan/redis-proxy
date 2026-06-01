package com.zuomagai.redisproxy.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityTarget;
import com.zuomagai.redisproxy.controlplane.model.RouteStatus;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ObservabilityServiceTest {
    private ObservabilityService service;
    private HttpServer server;
    private final AtomicInteger otlpWrites = new AtomicInteger();
    private final AtomicInteger influxWrites = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void collectsMetricsAndDebugTopN() throws Exception {
        startServer();
        service = new ObservabilityService(new ObjectMapper());

        service.register(target("proxy-1", baseUrl(), "go"));
        service.collectNow("proxy-1");

        assertThat(service.targets()).hasSize(1);
        assertThat(service.targets().getFirst().healthy()).isTrue();
        assertThat(service.targets().getFirst().resourceAttributes())
                .containsEntry("service.namespace", "redis-proxy")
                .containsEntry("service.name", "redis-proxy-dataplane")
                .containsEntry("service.instance.id", "proxy-1")
                .containsEntry("deployment.environment.name", "test")
                .containsEntry("redis.proxy.dataplane", "go")
                .containsEntry("redis.proxy.cluster", "redis-a");
        assertThat(service.summary().totals().governanceRejectTotal()).isEqualTo(1.0);
        assertThat(service.summary().totals().keyGovernanceDecisionTotal()).isEqualTo(2.0);
        assertThat(service.summary().totals().largeResponseTotal()).isEqualTo(3.0);
        assertThat(service.summary().totals().slowQueryObservedTotal()).isEqualTo(2.0);
        assertThat(service.hotKeys(null, "app-a", "GET", 10))
                .extracting("key")
                .containsExactly("app-a:1");
        assertThat(service.hotKeys(null, "app-a", "GET", 10).getFirst().resourceAttributes())
                .containsEntry("service.instance.id", "proxy-1");
        assertThat(service.largeKeys("proxy-1", null, null, 10).getFirst().key())
                .isEqualTo("app-a:big");
        assertThat(service.largeKeys("proxy-1", null, null, 10).getFirst().resourceAttributes())
                .containsEntry("service.name", "redis-proxy-dataplane");
        assertThat(service.slowQueries(null, "app-a", "GET", 10).getFirst().key())
                .isEqualTo("app-a:slow");
        assertThat(service.history("redis_proxy_auth_total", null, null, 60, null, null, null).points())
                .isNotEmpty();
        assertThat(service.prometheus()).contains("redis_proxy_control_plane_slow_query_observed_total");
        assertThat(service.routeConvergence(routeStatus(2, "sha256:abc")).status()).isEqualTo("CONVERGED");
    }

    @Test
    void overwritesAndDeletesTargets() throws Exception {
        startServer();
        service = new ObservabilityService(new ObjectMapper());

        service.register(target("proxy-1", baseUrl(), "go"));
        ObservabilityTarget next = target("proxy-1", baseUrl(), "java");
        next.setPollIntervalSeconds(0);
        service.register(next);

        assertThat(service.targets()).hasSize(1);
        assertThat(service.targets().getFirst().dataplane()).isEqualTo("java");
        assertThat(service.targets().getFirst().pollIntervalSeconds()).isEqualTo(15);

        service.delete("proxy-1");

        assertThat(service.targets()).isEmpty();
        assertThat(service.summary().targets()).isEmpty();
    }

    @Test
    void failedCollectionMarksTargetUnhealthyAndKeepsPreviousSnapshot() throws Exception {
        startServer();
        service = new ObservabilityService(new ObjectMapper());
        service.register(target("proxy-1", baseUrl(), "go"));
        service.collectNow("proxy-1");

        server.stop(0);
        server = null;
        service.collectNow("proxy-1");

        assertThat(service.targets().getFirst().healthy()).isFalse();
        assertThat(service.targets().getFirst().lastError()).isNotBlank();
        assertThat(service.hotKeys(null, null, null, 10))
                .extracting("key")
                .containsExactly("app-a:1");
        assertThat(service.slowQueries(null, null, null, 10))
                .extracting("key")
                .containsExactly("app-a:slow");
        assertThat(service.routeConvergence(routeStatus(2, "sha256:abc")).status()).isEqualTo("UNREACHABLE");
    }

    @Test
    void routeConvergenceClassifiesStaleAndDrift() throws Exception {
        startServer();
        service = new ObservabilityService(new ObjectMapper());
        service.register(target("proxy-1", baseUrl(), "go"));
        service.collectNow("proxy-1");

        assertThat(service.routeConvergence(routeStatus(3, "sha256:abc")).status()).isEqualTo("STALE");
        assertThat(service.routeConvergence(routeStatus(2, "sha256:def")).status()).isEqualTo("DRIFT");
    }

    @Test
    void rejectsInvalidTarget() {
        service = new ObservabilityService(new ObjectMapper());

        assertThatThrownBy(() -> service.register(new ObservabilityTarget()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxyId");
    }

    @Test
    void writesExternalStoresWithoutAffectingMemoryQueries() throws Exception {
        startServer();
        ObservabilityProperties otlp = new ObservabilityProperties();
        otlp.getStorage().setType("otlp");
        otlp.getStorage().getOtlp().setEndpoint(baseUrl());
        service = new ObservabilityService(new ObjectMapper(), otlp);
        service.register(target("proxy-1", baseUrl(), "go"));
        service.collectNow("proxy-1");

        assertThat(otlpWrites.get()).isGreaterThanOrEqualTo(1);
        assertThat(service.slowQueries(null, null, null, 10)).isNotEmpty();

        service.stop();
        ObservabilityProperties influx = new ObservabilityProperties();
        influx.getStorage().setType("influx");
        influx.getStorage().getInflux().setUrl(baseUrl());
        service = new ObservabilityService(new ObjectMapper(), influx);
        service.register(target("proxy-2", baseUrl(), "go"));
        service.collectNow("proxy-2");

        assertThat(influxWrites.get()).isGreaterThanOrEqualTo(1);
    }

    private void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = metrics().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/debug/hot-keys", exchange -> {
            byte[] body = "[{\"namespace\":\"app-a\",\"command\":\"GET\",\"key\":\"app-a:1\",\"count\":12}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/debug/large-keys", exchange -> {
            byte[] body = "[{\"namespace\":\"app-a\",\"command\":\"GET\",\"key\":\"app-a:big\",\"count\":2,\"maxRequestBytes\":80,\"maxResponseBytes\":2048,\"lastSeenUnix\":1}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/debug/slow-queries", exchange -> {
            byte[] body = "[{\"namespace\":\"app-a\",\"command\":\"GET\",\"key\":\"app-a:slow\",\"count\":2,\"maxEndToEndMillis\":120,\"maxBackendMillis\":80,\"lastSeenUnix\":1}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/debug/route-snapshot", exchange -> {
            byte[] body = "{\"proxyId\":\"proxy-1\",\"epoch\":2,\"configHash\":\"sha256:abc\",\"lastApplyResult\":\"success\",\"lastApplyTime\":10,\"lastPollTime\":11}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/logs", exchange -> {
            otlpWrites.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v2/write", exchange -> {
            influxWrites.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ObservabilityTarget target(String proxyId, String adminUrl, String dataplane) {
        ObservabilityTarget target = new ObservabilityTarget();
        target.setProxyId(proxyId);
        target.setAdminUrl(adminUrl);
        target.setDataplane(dataplane);
        target.setCluster("redis-a");
        target.setPollIntervalSeconds(60);
        target.setDeploymentEnvironmentName("test");
        return target;
    }

    private static String metrics() {
        return """
                redis_proxy_auth_total{namespace="app-a",result="success"} 4
                redis_proxy_governance_reject_total{namespace="app-a",command="FLUSHALL",reason="global_denied_command"} 1
                redis_proxy_governance_warn_total{namespace="app-a",command="KEYS",reason="warn_only_command"} 1
                redis_proxy_namespace_limit_reject_total{namespace="limited",limit="qps"} 1
                redis_proxy_key_governance_reject_total{namespace="app-a",rule="hot",command="GET",reason="qps_limit"} 1
                redis_proxy_key_governance_decisions_total{namespace="app-a",rule="hot",command="GET",result="reject",reason="qps_limit"} 2
                redis_proxy_hot_key_observed_total{namespace="app-a",command="GET"} 12
                redis_proxy_hot_key_dropped_total{namespace="app-a",command="GET"} 1
                redis_proxy_hot_key_tracked_keys 1
                redis_proxy_large_key_observed_total{namespace="app-a",command="GET",direction="response"} 2
                redis_proxy_large_key_dropped_total{namespace="app-a",command="GET"} 1
                redis_proxy_large_key_unsupported_total{command="SCAN",direction="response"} 1
                redis_proxy_large_key_tracked_keys 1
                redis_proxy_large_response_total{command="GET"} 3
                redis_proxy_slow_query_observed_total{namespace="app-a",command="GET",trigger="both"} 2
                redis_proxy_slow_query_dropped_total{namespace="app-a",command="GET"} 1
                redis_proxy_slow_query_unsupported_total{command="SCAN"} 1
                redis_proxy_slow_query_tracked_keys 1
                """;
    }

    private static RouteStatus routeStatus(long epoch, String hash) {
        return new RouteStatus(2, epoch, 2, epoch, hash, "redis-a", List.of(), List.of("redis-a"), null);
    }
}
