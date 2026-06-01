package com.zuomagai.redisproxy.controlplane.service;

import com.zuomagai.redisproxy.controlplane.model.ConfigVersion;
import com.zuomagai.redisproxy.controlplane.model.ProxyConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcConfigRepository implements ConfigRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcConfigRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ConfigVersion initializeIfEmpty(ProxyConfig initialConfig) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM config_versions", Integer.class);
        if (count != null && count > 0) {
            return current();
        }
        return saveAndActivate(initialConfig, "system", "initial-config", "INIT", "APPROVED", null);
    }

    @Override
    @Transactional
    public ConfigVersion saveAndActivate(ProxyConfig config, String operator, String reason, String action, String approvalStatus, Long rollbackFromVersionId) {
        long versionId = nextVersionId();
        Instant publishedAt = Instant.now();
        jdbc.update("""
                        INSERT INTO config_versions(
                            version_id, published_at, operator, reason, action, approval_status,
                            rollback_from_version_id, route_epoch, config_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                versionId,
                publishedAt.toString(),
                operator,
                reason,
                action,
                approvalStatus,
                rollbackFromVersionId,
                config.getRouting().getRouteEpoch(),
                writeConfig(config));
        int updated = jdbc.update(
                "UPDATE current_config SET version_id = ?, updated_at = ? WHERE id = 1",
                versionId,
                Instant.now().toString());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO current_config(id, version_id, updated_at) VALUES (1, ?, ?)",
                    versionId,
                    Instant.now().toString());
        }
        return version(versionId);
    }

    @Override
    public ConfigVersion current() {
        List<ConfigVersion> versions = jdbc.query("""
                        SELECT v.*
                        FROM config_versions v
                        JOIN current_config c ON c.version_id = v.version_id
                        WHERE c.id = 1
                        """,
                this::mapVersion);
        if (versions.isEmpty()) {
            throw new IllegalStateException("current config is not initialized");
        }
        return versions.getFirst();
    }

    @Override
    public List<ConfigVersion> versions() {
        return jdbc.query("SELECT * FROM config_versions ORDER BY version_id", this::mapVersion);
    }

    @Override
    public Optional<ConfigVersion> findByVersionId(long versionId) {
        List<ConfigVersion> versions = jdbc.query("SELECT * FROM config_versions WHERE version_id = ?", this::mapVersion, versionId);
        return versions.stream().findFirst();
    }

    @Override
    public Optional<ConfigVersion> findByRouteEpoch(long routeEpoch) {
        List<ConfigVersion> versions = jdbc.query(
                "SELECT * FROM config_versions WHERE route_epoch = ? ORDER BY version_id DESC",
                this::mapVersion,
                routeEpoch);
        return versions.stream().findFirst();
    }

    private ConfigVersion version(long versionId) {
        return findByVersionId(versionId)
                .orElseThrow(() -> new IllegalStateException("created config version not found: " + versionId));
    }

    private long nextVersionId() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(version_id), 0) FROM config_versions", Long.class);
        return (max == null ? 0 : max) + 1;
    }

    private ConfigVersion mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new ConfigVersion(
                rs.getLong("version_id"),
                Instant.parse(rs.getString("published_at")),
                rs.getString("operator"),
                rs.getString("reason"),
                rs.getString("action"),
                rs.getString("approval_status"),
                nullableLong(rs, "rollback_from_version_id"),
                rs.getLong("route_epoch"),
                readConfig(rs.getString("config_json")));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String writeConfig(ProxyConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("failed to serialize proxy config", error);
        }
    }

    private ProxyConfig readConfig(String json) {
        try {
            return objectMapper.readValue(json, ProxyConfig.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize proxy config", error);
        }
    }
}
