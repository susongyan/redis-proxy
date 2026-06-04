package com.zuomagai.redisproxy.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zuomagai.redisproxy.controlplane.model.ConfigVersion;
import com.zuomagai.redisproxy.controlplane.model.ProxyConfig;
import com.zuomagai.redisproxy.controlplane.model.PublishRequest;
import com.zuomagai.redisproxy.controlplane.model.RollbackRequest;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityProperties;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ControlPlanePersistenceTest {
    @Test
    void configServiceInitializesAndRestoresCurrentConfigFromDatabase() {
        JdbcTemplate jdbc = jdbcTemplate();
        ObjectMapper mapper = new ObjectMapper();

        ConfigService service = new ConfigService(new JdbcConfigRepository(jdbc, mapper));
        assertThat(service.versions()).hasSize(1);
        assertThat(service.get().getRouting().getRouteEpoch()).isEqualTo(1);

        ProxyConfig next = service.get();
        next.getRouting().setRouteEpoch(2);
        ConfigVersion published = service.publish(publishRequest(next, "alice", "persistent publish"));

        assertThat(published.versionId()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM config_versions", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT version_id FROM current_config WHERE id = 1", Long.class)).isEqualTo(2L);

        ConfigService restarted = new ConfigService(new JdbcConfigRepository(jdbc, mapper));

        assertThat(restarted.get().getRouting().getRouteEpoch()).isEqualTo(2);
        assertThat(restarted.routeStatus().currentVersionId()).isEqualTo(2);
        assertThat(restarted.version(2).reason()).isEqualTo("persistent publish");
        assertThat(restarted.versions()).hasSize(2);
    }

    @Test
    void stalePublishDoesNotCreateDatabaseVersionAndRollbackPersistsMetadata() {
        JdbcTemplate jdbc = jdbcTemplate();
        ConfigService service = new ConfigService(new JdbcConfigRepository(jdbc, new ObjectMapper()));

        ProxyConfig next = service.get();
        next.getRouting().setRouteEpoch(2);
        service.publish(publishRequest(next, "alice", "publish"));

        ProxyConfig stale = service.get();
        stale.getRouting().setRouteEpoch(2);
        assertThatThrownBy(() -> service.publish(publishRequest(stale, "alice", "stale")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM config_versions", Integer.class)).isEqualTo(2);

        RollbackRequest rollback = new RollbackRequest();
        rollback.setVersionId(1L);
        rollback.setOperator("bob");
        rollback.setReason("rollback");
        ConfigVersion rolled = service.rollback(rollback);

        assertThat(rolled.versionId()).isEqualTo(3);
        assertThat(rolled.routeEpoch()).isEqualTo(3);
        assertThat(rolled.rollbackFromVersionId()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT rollback_from_version_id FROM config_versions WHERE version_id = 3", Long.class)).isEqualTo(1L);
    }

    @Test
    void observabilityTargetsAreRestoredFromDatabase() {
        JdbcTemplate jdbc = jdbcTemplate();
        JdbcObservabilityTargetRepository repository = new JdbcObservabilityTargetRepository(jdbc);
        ObjectMapper mapper = new ObjectMapper();

        ObservabilityService service = new ObservabilityService(mapper, new ObservabilityProperties(), repository, java.net.http.HttpClient.newHttpClient());
        service.register(target("proxy-1", "http://127.0.0.1:18080", "go"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM observability_targets", Integer.class)).isEqualTo(1);
        service.stop();

        ObservabilityService restarted = new ObservabilityService(mapper, new ObservabilityProperties(), repository, java.net.http.HttpClient.newHttpClient());
        assertThat(restarted.targets()).hasSize(1);
        assertThat(restarted.targets().getFirst().proxyId()).isEqualTo("proxy-1");
        assertThat(restarted.targets().getFirst().group()).isEqualTo("frontend");
        assertThat(restarted.targets().getFirst().advertiseIp()).isEqualTo("10.0.0.1");
        assertThat(restarted.targets().getFirst().advertisePort()).isEqualTo(6379);
        assertThat(restarted.targets().getFirst().dataplane()).isEqualTo("go");

        restarted.delete("proxy-1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM observability_targets", Integer.class)).isEqualTo(0);
        restarted.stop();
    }

    private static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new JdbcTemplate(dataSource);
    }

    private static PublishRequest publishRequest(ProxyConfig config, String operator, String reason) {
        PublishRequest request = new PublishRequest();
        request.setConfig(config);
        request.setOperator(operator);
        request.setReason(reason);
        return request;
    }

    private static ObservabilityTarget target(String proxyId, String adminUrl, String dataplane) {
        ObservabilityTarget target = new ObservabilityTarget();
        target.setProxyId(proxyId);
        target.setGroup("frontend");
        target.setAdvertiseIp("10.0.0.1");
        target.setAdvertisePort(6379);
        target.setAdminUrl(adminUrl);
        target.setDataplane(dataplane);
        target.setCluster("redis-a");
        target.setPollIntervalSeconds(60);
        target.setDeploymentEnvironmentName("test");
        return target;
    }
}
