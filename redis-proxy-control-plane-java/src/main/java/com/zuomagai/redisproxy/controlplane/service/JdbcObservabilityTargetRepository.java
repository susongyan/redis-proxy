package com.zuomagai.redisproxy.controlplane.service;

import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityTarget;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcObservabilityTargetRepository implements ObservabilityTargetRepository {
    private final JdbcTemplate jdbc;

    public JdbcObservabilityTargetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void save(ObservabilityTarget target) {
        int updated = jdbc.update("""
                        UPDATE observability_targets
                        SET admin_url = ?, dataplane = ?, cluster_name = ?, poll_interval_seconds = ?,
                            service_namespace = ?, service_name = ?, service_instance_id = ?,
                            deployment_environment_name = ?, updated_at = ?
                        WHERE proxy_id = ?
                        """,
                target.getAdminUrl(),
                target.getDataplane(),
                target.getCluster(),
                target.getPollIntervalSeconds(),
                target.getServiceNamespace(),
                target.getServiceName(),
                target.getServiceInstanceId(),
                target.getDeploymentEnvironmentName(),
                Instant.now().toString(),
                target.getProxyId());
        if (updated == 0) {
            String now = Instant.now().toString();
            jdbc.update("""
                            INSERT INTO observability_targets(
                                proxy_id, admin_url, dataplane, cluster_name, poll_interval_seconds,
                                service_namespace, service_name, service_instance_id,
                                deployment_environment_name, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    target.getProxyId(),
                    target.getAdminUrl(),
                    target.getDataplane(),
                    target.getCluster(),
                    target.getPollIntervalSeconds(),
                    target.getServiceNamespace(),
                    target.getServiceName(),
                    target.getServiceInstanceId(),
                    target.getDeploymentEnvironmentName(),
                    now,
                    now);
        }
    }

    @Override
    public List<ObservabilityTarget> findAll() {
        return jdbc.query("SELECT * FROM observability_targets ORDER BY proxy_id", this::mapTarget);
    }

    @Override
    public void delete(String proxyId) {
        jdbc.update("DELETE FROM observability_targets WHERE proxy_id = ?", proxyId);
    }

    private ObservabilityTarget mapTarget(ResultSet rs, int rowNum) throws SQLException {
        ObservabilityTarget target = new ObservabilityTarget();
        target.setProxyId(rs.getString("proxy_id"));
        target.setAdminUrl(rs.getString("admin_url"));
        target.setDataplane(rs.getString("dataplane"));
        target.setCluster(rs.getString("cluster_name"));
        target.setPollIntervalSeconds(rs.getInt("poll_interval_seconds"));
        target.setServiceNamespace(rs.getString("service_namespace"));
        target.setServiceName(rs.getString("service_name"));
        target.setServiceInstanceId(rs.getString("service_instance_id"));
        target.setDeploymentEnvironmentName(rs.getString("deployment_environment_name"));
        return target;
    }
}
