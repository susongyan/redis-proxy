package com.example.redisproxy.dataplane.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.redisproxy.dataplane.backend.BackendPool;
import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteResolverTest {
    @Test
    void appliesClusterSlotsAndRoutesByCache() {
        RouteResolver resolver = resolver("127.0.0.1:7100", "127.0.0.1:7101");
        resolver.applyClusterSlots(Unpooled.copiedBuffer(
                "*2\r\n" +
                        "*3\r\n:0\r\n:8191\r\n*2\r\n$24\r\nredis-proxy-cluster-7100\r\n:7100\r\n" +
                        "*3\r\n:8192\r\n:16383\r\n*2\r\n$24\r\nredis-proxy-cluster-7101\r\n:7101\r\n",
                StandardCharsets.US_ASCII));

        assertThat(resolver.slotCoverage()).isEqualTo(16384);
        assertThat(resolver.route(requestForSlotAtLeast(8192))).isEqualTo("127.0.0.1:7101");
    }

    @Test
    void normalizesContainerHostnameByConfiguredPort() {
        RouteResolver resolver = resolver("127.0.0.1:7100", "127.0.0.1:7101");
        assertThat(resolver.normalizeAddr("redis-proxy-cluster-7101:7101")).isEqualTo("127.0.0.1:7101");
    }

    @Test
    void askDoesNotUpdateLongLivedSlotCache() {
        RouteResolver resolver = resolver("127.0.0.1:7100", "127.0.0.1:7101");
        resolver.updateMoved(Unpooled.copiedBuffer("-ASK 42 127.0.0.1:7101\r\n", StandardCharsets.US_ASCII), null);
        assertThat(resolver.slotCoverage()).isZero();
    }

    @Test
    void askTargetNormalizesWithinOriginalCluster() {
        ProxyProperties properties = properties("127.0.0.1:7100");
        ProxyProperties.Cluster gray = new ProxyProperties.Cluster();
        gray.setName("redis-b");
        gray.setNodes(List.of("127.0.0.1:7200"));
        properties.getBackends().setClusters(List.of(properties.getBackends().getClusters().getFirst(), gray));
        RouteResolver resolver = new RouteResolver(properties, new SimpleMeterRegistry());

        String target = resolver.askTarget(Unpooled.copiedBuffer("-ASK 42 redis-proxy-cluster-7200:7200\r\n", StandardCharsets.US_ASCII), "redis-b", null);

        assertThat(target).isEqualTo("127.0.0.1:7200");
        assertThat(resolver.clusterSlotCoverage("redis-b")).isZero();
    }

    @Test
    void movedUpdatesSingleSlotCache() {
        RouteResolver resolver = resolver("127.0.0.1:7100", "127.0.0.1:7101");
        resolver.updateMoved(Unpooled.copiedBuffer("-MOVED 42 127.0.0.1:7101\r\n", StandardCharsets.US_ASCII), null);
        assertThat(resolver.slotCoverage()).isEqualTo(1);
        assertThat(resolver.clusterSlotOwners("redis-a")).containsExactly("127.0.0.1:7101");
    }

    @Test
    void routeRuleSelectsGrayClusterByPrefix() {
        ProxyProperties properties = properties("127.0.0.1:6379");
        ProxyProperties.Cluster gray = new ProxyProperties.Cluster();
        gray.setName("redis-b");
        gray.setNodes(List.of("127.0.0.1:6380"));
        properties.getBackends().setClusters(List.of(properties.getBackends().getClusters().getFirst(), gray));
        ProxyProperties.RouteRule rule = new ProxyProperties.RouteRule();
        rule.setName("gray-user");
        rule.setCluster("redis-b");
        rule.setKeyPrefix("user:");
        rule.setTrafficPercent(100);
        properties.getRouting().setRules(List.of(rule));

        RouteResolver resolver = new RouteResolver(properties, new SimpleMeterRegistry());

        assertThat(resolver.route(request("GET", "user:1"))).isEqualTo("127.0.0.1:6380");
        assertThat(resolver.route(request("GET", "order:1"))).isEqualTo("127.0.0.1:6379");
    }

    @Test
    void applyConfigAcceptsHigherEpochAndRejectsStale() {
        ProxyProperties properties = properties("127.0.0.1:6379");
        RouteResolver resolver = new RouteResolver(properties, new SimpleMeterRegistry());
        BackendPool backendPool = mock(BackendPool.class);

        ProxyProperties stale = properties("127.0.0.1:6379");
        stale.getRouting().setRouteEpoch(1);
        RouteResolver.ApplyResult staleResult = resolver.applyConfig(stale, backendPool);
        assertThat(staleResult.applied()).isFalse();
        assertThat(staleResult.result()).isEqualTo("stale_epoch");

        ProxyProperties next = properties("127.0.0.1:6379");
        ProxyProperties.Cluster gray = new ProxyProperties.Cluster();
        gray.setName("redis-b");
        gray.setNodes(List.of("127.0.0.1:6380"));
        next.getBackends().setClusters(List.of(next.getBackends().getClusters().getFirst(), gray));
        next.getRouting().setRouteEpoch(2);
        ProxyProperties.RouteRule rule = new ProxyProperties.RouteRule();
        rule.setName("gray-user");
        rule.setCluster("redis-b");
        rule.setKeyPrefix("user:");
        rule.setTrafficPercent(100);
        next.getRouting().setRules(List.of(rule));

        RouteResolver.ApplyResult result = resolver.applyConfig(next, backendPool);
        assertThat(result.applied()).isTrue();
        assertThat(resolver.currentEpoch()).isEqualTo(2);
        assertThat(resolver.routeDecision(request("GET", "user:1")).cluster()).isEqualTo("redis-b");
        verify(backendPool).ensureAll(List.of("127.0.0.1:6380"));
    }

    private static RouteResolver resolver(String... nodes) {
        return new RouteResolver(properties(nodes), new SimpleMeterRegistry());
    }

    private static ProxyProperties properties(String... nodes) {
        ProxyProperties properties = new ProxyProperties();
        properties.setMode("cluster");
        ProxyProperties.Cluster cluster = new ProxyProperties.Cluster();
        cluster.setName("redis-a");
        cluster.setNodes(List.of(nodes));
        properties.getBackends().setClusters(List.of(cluster));
        properties.getRouting().setDefaultCluster("redis-a");
        return properties;
    }

    private static RespRequest requestForSlotAtLeast(int minSlot) {
        for (int i = 0; i < 100_000; i++) {
            byte[] key = ("key-" + i).getBytes(StandardCharsets.US_ASCII);
            if (RedisSlot.slot(key) >= minSlot) {
                return new RespRequest(Unpooled.EMPTY_BUFFER, List.of("GET".getBytes(StandardCharsets.US_ASCII), key));
            }
        }
        throw new IllegalStateException("test key not found");
    }

    private static RespRequest request(String... args) {
        return new RespRequest(
                Unpooled.EMPTY_BUFFER,
                java.util.Arrays.stream(args)
                        .map(arg -> arg.getBytes(StandardCharsets.US_ASCII))
                        .toList());
    }
}
