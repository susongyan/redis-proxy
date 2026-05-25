package com.example.redisproxy.controlplane.service;

import com.example.redisproxy.controlplane.model.ConfigVersion;
import com.example.redisproxy.controlplane.model.ProxyConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class MemoryConfigRepository implements ConfigRepository {
    private final ObjectMapper objectMapper;
    private final AtomicLong nextVersionId = new AtomicLong(1);
    private final AtomicReference<ConfigVersion> current = new AtomicReference<>();
    private final CopyOnWriteArrayList<ConfigVersion> versions = new CopyOnWriteArrayList<>();

    MemoryConfigRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized ConfigVersion initializeIfEmpty(ProxyConfig initialConfig) {
        ConfigVersion existing = current.get();
        if (existing != null) {
        return copyVersion(existing);
        }
        return saveAndActivate(initialConfig, "system", "initial-config", "INIT", "APPROVED", null);
    }

    @Override
    public synchronized ConfigVersion saveAndActivate(ProxyConfig config, String operator, String reason, String action, String approvalStatus, Long rollbackFromVersionId) {
        ConfigVersion version = new ConfigVersion(
                nextVersionId.getAndIncrement(),
                Instant.now(),
                operator,
                reason,
                action,
                approvalStatus,
                rollbackFromVersionId,
                config.getRouting().getRouteEpoch(),
                copyConfig(config));
        current.set(copyVersion(version));
        versions.add(copyVersion(version));
        return copyVersion(version);
    }

    @Override
    public ConfigVersion current() {
        ConfigVersion version = current.get();
        if (version == null) {
            throw new IllegalStateException("config repository is not initialized");
        }
        return copyVersion(version);
    }

    @Override
    public List<ConfigVersion> versions() {
        return versions.stream().map(this::copyVersion).toList();
    }

    @Override
    public Optional<ConfigVersion> findByVersionId(long versionId) {
        return versions.stream()
                .filter(version -> version.versionId() == versionId)
                .findFirst()
                .map(this::copyVersion);
    }

    @Override
    public Optional<ConfigVersion> findByRouteEpoch(long routeEpoch) {
        return versions.stream()
                .filter(version -> version.routeEpoch() == routeEpoch)
                .reduce((first, second) -> second)
                .map(this::copyVersion);
    }

    private ProxyConfig copyConfig(ProxyConfig config) {
        return objectMapper.convertValue(config, ProxyConfig.class);
    }

    private ConfigVersion copyVersion(ConfigVersion version) {
        return new ConfigVersion(
                version.versionId(),
                version.publishedAt(),
                version.operator(),
                version.reason(),
                version.action(),
                version.approvalStatus(),
                version.rollbackFromVersionId(),
                version.routeEpoch(),
                copyConfig(version.config()));
    }
}
