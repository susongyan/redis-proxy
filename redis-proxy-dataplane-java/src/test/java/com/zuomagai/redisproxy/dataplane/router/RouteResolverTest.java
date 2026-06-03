package com.zuomagai.redisproxy.dataplane.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.zuomagai.redisproxy.dataplane.backend.BackendPool;
import com.zuomagai.redisproxy.dataplane.config.ProxyProperties;
import com.zuomagai.redisproxy.dataplane.protocol.RespRequest;
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
    void matchAllRuleSelectsClusterAndAffectsHash() {
        ProxyProperties properties = properties("127.0.0.1:6379");
        ProxyProperties.Cluster gray = new ProxyProperties.Cluster();
        gray.setName("redis-b");
        gray.setNodes(List.of("127.0.0.1:6380"));
        properties.getBackends().setClusters(List.of(properties.getBackends().getClusters().getFirst(), gray));
        ProxyProperties.RouteRule rule = new ProxyProperties.RouteRule();
        rule.setName("cluster-switch-1");
        rule.setCluster("redis-b");
        rule.setMatchAll(true);
        rule.setTrafficPercent(100);
        properties.getRouting().setRules(List.of(rule));
        String baseHash = RouteConfigHash.hash(properties);

        RouteResolver resolver = new RouteResolver(properties, new SimpleMeterRegistry());

        assertThat(resolver.route(request("GET", "order:1"))).isEqualTo("127.0.0.1:6380");
        rule.setMatchAll(false);
        assertThat(RouteConfigHash.hash(properties)).isNotEqualTo(baseHash);
    }

    @Test
    void backendAuthAffectsHashAndRequiresPassword() {
        ProxyProperties properties = properties("127.0.0.1:6379");
        String baseHash = RouteConfigHash.hash(properties);

        properties.getBackends().getClusters().getFirst().getAuth().setEnabled(true);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth.password");

        properties.getBackends().getClusters().getFirst().getAuth().setUsername("default");
        properties.getBackends().getClusters().getFirst().getAuth().setPassword("secret");
        properties.validate();
        assertThat(RouteConfigHash.hash(properties)).isNotEqualTo(baseHash);
    }

    @Test
    void routeRuleSelectsClusterByNamespacePatternAndHashTag() {
        ProxyProperties properties = properties("127.0.0.1:6379");
        ProxyProperties.Cluster redisB = new ProxyProperties.Cluster();
        redisB.setName("redis-b");
        redisB.setNodes(List.of("127.0.0.1:6380"));
        ProxyProperties.Cluster redisC = new ProxyProperties.Cluster();
        redisC.setName("redis-c");
        redisC.setNodes(List.of("127.0.0.1:6381"));
        properties.getBackends().setClusters(List.of(properties.getBackends().getClusters().getFirst(), redisB, redisC));
        ProxyProperties.Namespace namespace = new ProxyProperties.Namespace();
        namespace.setName("app-a");
        namespace.setToken("token-a");
        properties.getGovernance().setNamespaces(List.of(namespace));
        ProxyProperties.RouteRule pattern = new ProxyProperties.RouteRule();
        pattern.setName("app-profile");
        pattern.setCluster("redis-b");
        pattern.setNamespace("app-a");
        pattern.setKeyPattern("user:*:profile");
        pattern.setTrafficPercent(100);
        ProxyProperties.RouteRule hashTag = new ProxyProperties.RouteRule();
        hashTag.setName("order-tag");
        hashTag.setCluster("redis-c");
        hashTag.setHashTag("order");
        hashTag.setTrafficPercent(100);
        properties.getRouting().setRules(List.of(pattern, hashTag));

        RouteResolver resolver = new RouteResolver(properties, new SimpleMeterRegistry());

        assertThat(resolver.routeDecision(request("GET", "user:42:profile"), "app-a").cluster()).isEqualTo("redis-b");
        assertThat(resolver.routeDecision(request("GET", "user:42:profile"), "app-b").cluster()).isEqualTo("redis-a");
        assertThat(resolver.routeDecision(request("GET", "{order}:1")).cluster()).isEqualTo("redis-c");
    }

    @Test
    void applyConfigAcceptsHigherEpochAndRejectsStale() {
        ProxyProperties properties = properties("127.0.0.1:6379");
        properties.getInstance().setProxyId("proxy-java-1");
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
        next.getLimits().setLargeResponseBytes(2048);
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
        assertThat(resolver.limits().getLargeResponseBytes()).isEqualTo(2048);
        assertThat(resolver.snapshotInfo().proxyId()).isEqualTo("proxy-java-1");
        assertThat(resolver.snapshotInfo().configHash()).startsWith("sha256:");
        assertThat(resolver.snapshotInfo().lastApplyResult()).isEqualTo("success");
        verify(backendPool).ensureCluster(next.getBackends().getClusters().get(1));
    }

    @Test
    void routeConfigHashIgnoresLocalRuntimeFieldsAndChangesForSnapshotFields() {
        ProxyProperties properties = properties("127.0.0.1:6379");
        properties.validate();
        String base = RouteConfigHash.hash(properties);

        properties.getInstance().setProxyId("other");
        properties.getServer().setListen("0.0.0.0:9999");
        properties.getAdmin().setListen("0.0.0.0:19999");
        properties.getControlPlane().setEnabled(true);
        properties.getControlPlane().setUrl("http://127.0.0.1:8090/api/v1/config");
        assertThat(RouteConfigHash.hash(properties)).isEqualTo(base);

        properties.getRouting().setRouteEpoch(2);
        assertThat(RouteConfigHash.hash(properties)).isNotEqualTo(base);
    }

    @Test
    void backendAffinitySupportsClientAndKeyBasedStrategies() {
        ProxyProperties properties = properties("127.0.0.1:6379");
        RouteResolver resolver = new RouteResolver(properties, new SimpleMeterRegistry());
        RespRequest request = request("GET", "{user}:1");
        assertThat(resolver.backendAffinity(request, 123)).isEqualTo(123);

        properties = properties("127.0.0.1:6379");
        properties.getRouting().setBackendAffinityStrategy("keySlot");
        resolver = new RouteResolver(properties, new SimpleMeterRegistry());
        assertThat(resolver.backendAffinity(request, 123)).isEqualTo(request.arg(1).slot());

        properties = properties("127.0.0.1:6379");
        properties.getRouting().setBackendAffinityStrategy("hashTag");
        resolver = new RouteResolver(properties, new SimpleMeterRegistry());
        assertThat(resolver.backendAffinity(request, 123)).isEqualTo(request.arg(1).slot());
        assertThat(resolver.backendAffinity(request("PING"), 123)).isEqualTo(123);
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
