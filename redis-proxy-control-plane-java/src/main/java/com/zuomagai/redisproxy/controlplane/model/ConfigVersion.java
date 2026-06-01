package com.zuomagai.redisproxy.controlplane.model;

import java.time.Instant;

public record ConfigVersion(
        long versionId,
        Instant publishedAt,
        String operator,
        String reason,
        String action,
        String approvalStatus,
        Long rollbackFromVersionId,
        long routeEpoch,
        ProxyConfig config) {
}
