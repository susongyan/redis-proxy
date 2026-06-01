package com.zuomagai.redisproxy.controlplane.service;

import com.zuomagai.redisproxy.controlplane.model.ConfigVersion;
import com.zuomagai.redisproxy.controlplane.model.ProxyConfig;
import java.util.List;
import java.util.Optional;

public interface ConfigRepository {
    ConfigVersion initializeIfEmpty(ProxyConfig initialConfig);

    ConfigVersion saveAndActivate(
            ProxyConfig config,
            String operator,
            String reason,
            String action,
            String approvalStatus,
            Long rollbackFromVersionId);

    ConfigVersion current();

    List<ConfigVersion> versions();

    Optional<ConfigVersion> findByVersionId(long versionId);

    Optional<ConfigVersion> findByRouteEpoch(long routeEpoch);
}
