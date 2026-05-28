package com.example.redisproxy.controlplane.model;

import java.util.List;

public record RouteStatus(
        long currentVersionId,
        long routeEpoch,
        long expectedVersionId,
        long expectedRouteEpoch,
        String expectedConfigHash,
        String defaultCluster,
        List<ProxyConfig.RouteRule> rules,
        List<String> clusters,
        ConfigVersion lastPublished) {
}
