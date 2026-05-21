package com.example.redisproxy.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.redisproxy.controlplane.model.ConfigVersion;
import com.example.redisproxy.controlplane.model.ProxyConfig;
import com.example.redisproxy.controlplane.model.PublishRequest;
import com.example.redisproxy.controlplane.model.RollbackRequest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
        assertThat(service.update(config).getRouting().getRouteEpoch()).isEqualTo(config.getRouting().getRouteEpoch());
        assertThat(service.versions()).hasSize(2);
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
    void rejectsInvalidRoutingEpochAndRefreshInterval() {
        ConfigService service = new ConfigService();
        ProxyConfig config = service.get();
        config.getRouting().setRouteEpoch(-1);
        assertThatThrownBy(() -> service.update(config))
                .isInstanceOf(IllegalArgumentException.class);

        config.getRouting().setRouteEpoch(1);
        config.getRouting().setClusterSlotsRefreshIntervalSeconds(-1);
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

        assertThat(service.update(config).getRouting().getRules()).hasSize(1);
    }

    @Test
    void watchReturnsImmediatelyWhenCurrentEpochIsNewer() throws Exception {
        ConfigService service = new ConfigService();
        ProxyConfig config = service.get();
        config.getRouting().setRouteEpoch(2);
        service.update(config);

        Optional<ProxyConfig> watched = service.watch(1, Duration.ofSeconds(1)).get(1, TimeUnit.SECONDS);

        assertThat(watched).isPresent();
        assertThat(watched.get().getRouting().getRouteEpoch()).isEqualTo(2);
    }

    @Test
    void watchCompletesWhenHigherEpochIsPublished() throws Exception {
        ConfigService service = new ConfigService();
        CompletableFuture<Optional<ProxyConfig>> watch = service.watch(1, Duration.ofSeconds(5));
        assertThat(watch).isNotDone();

        ProxyConfig config = service.get();
        config.getRouting().setRouteEpoch(2);
        service.update(config);

        assertThat(watch.get(1, TimeUnit.SECONDS)).isPresent();
        assertThat(watch.get(1, TimeUnit.SECONDS).get().getRouting().getRouteEpoch()).isEqualTo(2);
    }

    @Test
    void watchTimesOutWhenEpochDoesNotAdvance() throws Exception {
        ConfigService service = new ConfigService();

        Optional<ProxyConfig> watched = service.watch(1, Duration.ofMillis(20)).get(1, TimeUnit.SECONDS);

        assertThat(watched).isEmpty();
    }

    @Test
    void publishCreatesVersionAndRejectsStaleEpoch() {
        ConfigService service = new ConfigService();
        ProxyConfig next = service.get();
        next.getRouting().setRouteEpoch(2);
        PublishRequest request = publishRequest(next, "alice", "gray user traffic");

        ConfigVersion version = service.publish(request);

        assertThat(version.versionId()).isEqualTo(2);
        assertThat(version.operator()).isEqualTo("alice");
        assertThat(version.reason()).isEqualTo("gray user traffic");
        assertThat(version.action()).isEqualTo("PUBLISH");
        assertThat(version.routeEpoch()).isEqualTo(2);
        assertThat(service.versions()).hasSize(2);
        assertThat(service.routeStatus().routeEpoch()).isEqualTo(2);

        ProxyConfig stale = service.get();
        stale.getRouting().setRouteEpoch(2);
        assertThatThrownBy(() -> service.publish(publishRequest(stale, "alice", "stale")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than current");
    }

    @Test
    void rollbackCopiesHistoricalContentWithHigherEpochAndWakesWatcher() throws Exception {
        ConfigService service = new ConfigService();
        ProxyConfig original = service.get();
        CompletableFuture<Optional<ProxyConfig>> watch = service.watch(2, Duration.ofSeconds(5));

        ProxyConfig next = service.get();
        ProxyConfig.Cluster gray = new ProxyConfig.Cluster();
        gray.setName("redis-b");
        gray.setNodes(List.of("127.0.0.1:6380"));
        next.getBackends().setClusters(List.of(next.getBackends().getClusters().getFirst(), gray));
        next.getRouting().setRouteEpoch(2);
        ProxyConfig.RouteRule rule = new ProxyConfig.RouteRule();
        rule.setName("gray-user");
        rule.setCluster("redis-b");
        rule.setKeyPrefix("user:");
        rule.setTrafficPercent(100);
        next.getRouting().setRules(List.of(rule));
        service.publish(publishRequest(next, "alice", "gray"));

        RollbackRequest rollback = new RollbackRequest();
        rollback.setVersionId(1L);
        rollback.setOperator("bob");
        rollback.setReason("rollback gray");
        ConfigVersion rolled = service.rollback(rollback);

        assertThat(rolled.action()).isEqualTo("ROLLBACK");
        assertThat(rolled.rollbackFromVersionId()).isEqualTo(1L);
        assertThat(rolled.routeEpoch()).isEqualTo(3);
        assertThat(rolled.config().getRouting().getRules()).isEmpty();
        assertThat(rolled.config().getRouting().getDefaultCluster()).isEqualTo(original.getRouting().getDefaultCluster());
        assertThat(watch.get(1, TimeUnit.SECONDS)).isPresent();
        assertThat(watch.get(1, TimeUnit.SECONDS).get().getRouting().getRouteEpoch()).isEqualTo(3);
    }

    @Test
    void diffAndStatusExposePublishState() {
        ConfigService service = new ConfigService();
        ProxyConfig next = service.get();
        next.getRouting().setRouteEpoch(2);
        next.getRouting().setDefaultCluster("redis-a");
        service.publish(publishRequest(next, "alice", "publish"));

        assertThat(service.diff(1, 2).changes()).anyMatch(change -> change.contains("routing.routeEpoch"));
        assertThat(service.version(2).operator()).isEqualTo("alice");
        assertThat(service.routeStatus().currentVersionId()).isEqualTo(2);
        assertThat(service.routeStatus().lastPublished().reason()).isEqualTo("publish");
    }

    @Test
    void validatesAndCopiesGovernanceConfig() {
        ConfigService service = new ConfigService();
        ProxyConfig next = service.get();
        next.getRouting().setRouteEpoch(2);
        next.getGovernance().setEnabled(true);
        ProxyConfig.Namespace namespace = new ProxyConfig.Namespace();
        namespace.setName("app-a");
        namespace.setToken("token-a");
        namespace.setAllowedKeyPrefixes(List.of("app-a:"));
        namespace.setDeniedCommands(List.of("flushall"));
        namespace.getLimits().setMaxConnections(10);
        namespace.getLimits().setMaxQps(100);
        namespace.getLimits().setMaxInflight(20);
        namespace.setDisabledKeys(List.of("app-a:blocked"));
        ProxyConfig.KeyRule rule = new ProxyConfig.KeyRule();
        rule.setName("hot");
        rule.setKeyPrefix("app-a:hot:");
        rule.setMaxQps(1);
        namespace.setKeyRules(List.of(rule));
        next.getGovernance().setNamespaces(List.of(namespace));

        ConfigVersion version = service.publish(publishRequest(next, "alice", "governance"));

        assertThat(version.config().getGovernance().isRequireAuth()).isTrue();
        assertThat(version.config().getGovernance().getNamespaces()).hasSize(1);
        assertThat(version.config().getGovernance().getNamespaces().getFirst().getDeniedCommands()).containsExactly("FLUSHALL");
        assertThat(version.config().getGovernance().getNamespaces().getFirst().getToken()).isEqualTo("token-a");
        assertThat(version.config().getGovernance().getNamespaces().getFirst().getLimits().getMaxQps()).isEqualTo(100);
        assertThat(version.config().getGovernance().getNamespaces().getFirst().getDisabledKeys()).containsExactly("app-a:blocked");
        assertThat(version.config().getGovernance().getNamespaces().getFirst().getKeyRules().getFirst().getName()).isEqualTo("hot");
        assertThat(service.diff(1, 2).changes()).anyMatch(change -> change.contains("governance"));

        RollbackRequest rollback = new RollbackRequest();
        rollback.setVersionId(2L);
        ConfigVersion rollbackVersion = service.rollback(rollback);
        assertThat(rollbackVersion.config().getRouting().getRouteEpoch()).isEqualTo(3);
        assertThat(rollbackVersion.config().getGovernance().getNamespaces().getFirst().getKeyRules().getFirst().getMaxQps()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidGovernanceConfig() {
        ConfigService service = new ConfigService();
        ProxyConfig next = service.get();
        next.getRouting().setRouteEpoch(2);
        next.getGovernance().setEnabled(true);
        ProxyConfig.Namespace namespace = new ProxyConfig.Namespace();
        namespace.setName("app-a");
        namespace.setToken("");
        next.getGovernance().setNamespaces(List.of(namespace));

        assertThatThrownBy(() -> service.publish(publishRequest(next, "alice", "bad governance")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");

        namespace.setToken("token-a");
        ProxyConfig.Namespace duplicate = new ProxyConfig.Namespace();
        duplicate.setName("app-a");
        duplicate.setToken("token-b");
        next.getGovernance().setNamespaces(List.of(namespace, duplicate));
        assertThatThrownBy(() -> service.publish(publishRequest(next, "alice", "duplicate governance")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");

        next.getGovernance().setKeyLimitWindowMillis(1000);
        next.getGovernance().setKeyLimitBucketMillis(333);
        next.getGovernance().setNamespaces(List.of(namespace));
        assertThatThrownBy(() -> service.publish(publishRequest(next, "alice", "bad window")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");

        next.getGovernance().setKeyLimitBucketMillis(100);
        namespace.getLimits().setMaxQps(-1);
        assertThatThrownBy(() -> service.publish(publishRequest(next, "alice", "bad namespace limit")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limits");

        namespace.getLimits().setMaxQps(0);
        ProxyConfig.KeyRule badRule = new ProxyConfig.KeyRule();
        badRule.setName("bad");
        namespace.setKeyRules(List.of(badRule));
        assertThatThrownBy(() -> service.publish(publishRequest(next, "alice", "bad key rule")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key rule");
    }

    private static PublishRequest publishRequest(ProxyConfig config, String operator, String reason) {
        PublishRequest request = new PublishRequest();
        request.setConfig(config);
        request.setOperator(operator);
        request.setReason(reason);
        return request;
    }
}
