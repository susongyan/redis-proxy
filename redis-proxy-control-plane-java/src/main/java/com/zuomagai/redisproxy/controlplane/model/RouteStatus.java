package com.zuomagai.redisproxy.controlplane.model;

import java.util.List;
import java.util.Optional;

public record RouteStatus(
        long currentVersionId,
        long routeEpoch,
        long expectedVersionId,
        long expectedRouteEpoch,
        String expectedConfigHash,
        String defaultCluster,
        List<ProxyConfig.RouteRule> rules,
        List<String> clusters,
        List<GroupRouteStatus> groups,
        ConfigVersion lastPublished) {

    public GroupRouteStatus expectedForGroup(String group) {
        String normalized = group == null || group.isBlank() ? "default" : group;
        Optional<GroupRouteStatus> matched = groups == null ? Optional.empty() : groups.stream()
                .filter(status -> normalized.equals(status.group()))
                .findFirst();
        return matched.orElseGet(() -> new GroupRouteStatus(
                "default",
                expectedVersionId,
                expectedRouteEpoch,
                expectedConfigHash,
                defaultCluster,
                clusters,
                rules));
    }

    public record GroupRouteStatus(
            String group,
            long expectedVersionId,
            long expectedRouteEpoch,
            String expectedConfigHash,
            String defaultCluster,
            List<String> clusters,
            List<ProxyConfig.RouteRule> rules) {}
}
