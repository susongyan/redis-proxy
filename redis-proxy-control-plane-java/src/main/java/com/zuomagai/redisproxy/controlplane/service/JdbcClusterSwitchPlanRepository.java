package com.zuomagai.redisproxy.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zuomagai.redisproxy.controlplane.model.ClusterSwitchPlan;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcClusterSwitchPlanRepository implements ClusterSwitchPlanRepository {
    private static final List<String> TERMINAL = List.of("COMPLETED", "ROLLED_BACK", "CANCELLED", "FAILED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcClusterSwitchPlanRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Override
    @Transactional
    public ClusterSwitchPlan save(ClusterSwitchPlan plan) {
        ClusterSwitchPlan copy = read(write(plan));
        Instant now = Instant.now();
        if (copy.getPlanId() == 0) {
            copy.setPlanId(nextPlanId());
            copy.setCreatedAt(now);
        }
        if (copy.getCreatedAt() == null) {
            copy.setCreatedAt(now);
        }
        copy.setUpdatedAt(now);
        int updated = jdbc.update("""
                        UPDATE cluster_switch_plans
                        SET source_cluster = ?, target_cluster = ?, mode = ?, status = ?,
                            updated_at = ?, plan_json = ?
                        WHERE plan_id = ?
                        """,
                copy.getSourceCluster(),
                copy.getTargetCluster(),
                copy.getMode(),
                copy.getStatus(),
                copy.getUpdatedAt().toString(),
                write(copy),
                copy.getPlanId());
        if (updated == 0) {
            jdbc.update("""
                            INSERT INTO cluster_switch_plans(
                                plan_id, source_cluster, target_cluster, mode, status,
                                created_at, updated_at, plan_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    copy.getPlanId(),
                    copy.getSourceCluster(),
                    copy.getTargetCluster(),
                    copy.getMode(),
                    copy.getStatus(),
                    copy.getCreatedAt().toString(),
                    copy.getUpdatedAt().toString(),
                    write(copy));
        }
        return findById(copy.getPlanId()).orElseThrow();
    }

    @Override
    public Optional<ClusterSwitchPlan> findById(long planId) {
        return jdbc.query("SELECT * FROM cluster_switch_plans WHERE plan_id = ?", this::mapPlan, planId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ClusterSwitchPlan> findAll() {
        return jdbc.query("SELECT * FROM cluster_switch_plans ORDER BY plan_id", this::mapPlan);
    }

    @Override
    public Optional<ClusterSwitchPlan> findActiveByProxyGroupAndSourceCluster(String proxyGroup, String sourceCluster) {
        String normalizedGroup = proxyGroup == null || proxyGroup.isBlank() ? "default" : proxyGroup;
        return jdbc.query(
                        "SELECT * FROM cluster_switch_plans WHERE source_cluster = ? ORDER BY plan_id",
                        this::mapPlan,
                        sourceCluster)
                .stream()
                .filter(plan -> normalizedGroup.equals(plan.getProxyGroup()))
                .filter(plan -> !TERMINAL.contains(plan.getStatus()))
                .findFirst();
    }

    private long nextPlanId() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(plan_id), 0) FROM cluster_switch_plans", Long.class);
        return (max == null ? 0 : max) + 1;
    }

    private ClusterSwitchPlan mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return read(rs.getString("plan_json"));
    }

    private String write(ClusterSwitchPlan plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("failed to serialize cluster switch plan", error);
        }
    }

    private ClusterSwitchPlan read(String json) {
        try {
            return objectMapper.readValue(json, ClusterSwitchPlan.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize cluster switch plan", error);
        }
    }
}
