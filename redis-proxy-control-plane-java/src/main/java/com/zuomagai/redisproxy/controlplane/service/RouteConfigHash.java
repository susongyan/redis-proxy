package com.zuomagai.redisproxy.controlplane.service;

import com.zuomagai.redisproxy.controlplane.model.ProxyConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class RouteConfigHash {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RouteConfigHash() {}

    static String hash(ProxyConfig config) {
        try {
            byte[] raw = MAPPER.writeValueAsBytes(canonical(config));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("compute route config hash", e);
        }
    }

    private static Map<String, Object> canonical(ProxyConfig c) {
        return map(
                "analysis", analysis(c.getAnalysis()),
                "backends", map("clusters", clusters(c.getBackends().getClusters())),
                "governance", governance(c.getGovernance()),
                "limits", map(
                        "largeResponseBytes", c.getLimits().getLargeResponseBytes(),
                        "maxPipelineDepth", c.getLimits().getMaxPipelineDepth(),
                        "maxRequestBytes", c.getLimits().getMaxRequestBytes(),
                        "maxResponseBytes", c.getLimits().getMaxResponseBytes(),
                        "pipelineFlushBatchSize", c.getLimits().getPipelineFlushBatchSize(),
                        "pipelineFlushMaxDelayMillis", c.getLimits().getPipelineFlushMaxDelayMillis()),
                "mode", text(c.getMode()),
                "routing", map(
                        "clusterSlotsRefreshIntervalSeconds", c.getRouting().getClusterSlotsRefreshIntervalSeconds(),
                        "backendAffinityStrategy", text(c.getRouting().getBackendAffinityStrategy()),
                        "defaultCluster", text(c.getRouting().getDefaultCluster()),
                        "routeEpoch", c.getRouting().getRouteEpoch(),
                        "rules", rules(c.getRouting().getRules())));
    }

    private static List<Object> clusters(List<ProxyConfig.Cluster> clusters) {
        List<Object> out = new ArrayList<>();
        for (ProxyConfig.Cluster cluster : list(clusters)) {
            out.add(map(
                    "name", text(cluster.getName()),
                    "nodes", strings(cluster.getNodes()),
                    "pool", map(
                            "connectionsPerNode", cluster.getPool().getConnectionsPerNode(),
                            "maxInflightPerConnection", cluster.getPool().getMaxInflightPerConnection())));
        }
        return out;
    }

    private static List<Object> rules(List<ProxyConfig.RouteRule> rules) {
        List<Object> out = new ArrayList<>();
        for (ProxyConfig.RouteRule rule : list(rules)) {
            out.add(map(
                    "cluster", text(rule.getCluster()),
                    "hashTag", text(rule.getHashTag()),
                    "keyPattern", text(rule.getKeyPattern()),
                    "keyPrefix", text(rule.getKeyPrefix()),
                    "name", text(rule.getName()),
                    "namespace", text(rule.getNamespace()),
                    "trafficPercent", rule.getTrafficPercent()));
        }
        return out;
    }

    private static Map<String, Object> analysis(ProxyConfig.Analysis analysis) {
        return map(
                "hotKey", map(
                        "bucketMillis", analysis.getHotKey().getBucketMillis(),
                        "enabled", analysis.getHotKey().isEnabled(),
                        "maxTrackedKeys", analysis.getHotKey().getMaxTrackedKeys(),
                        "metricsTopN", analysis.getHotKey().getMetricsTopN(),
                        "windowSeconds", analysis.getHotKey().getWindowSeconds()),
                "largeKey", map(
                        "bucketMillis", analysis.getLargeKey().getBucketMillis(),
                        "debugTopN", analysis.getLargeKey().getDebugTopN(),
                        "enabled", analysis.getLargeKey().isEnabled(),
                        "maxTrackedKeys", analysis.getLargeKey().getMaxTrackedKeys(),
                        "requestBytesThreshold", analysis.getLargeKey().getRequestBytesThreshold(),
                        "responseBytesThreshold", analysis.getLargeKey().getResponseBytesThreshold(),
                        "windowSeconds", analysis.getLargeKey().getWindowSeconds()),
                "slowQuery", map(
                        "backendThresholdMillis", analysis.getSlowQuery().getBackendThresholdMillis(),
                        "bucketMillis", analysis.getSlowQuery().getBucketMillis(),
                        "debugTopN", analysis.getSlowQuery().getDebugTopN(),
                        "enabled", analysis.getSlowQuery().isEnabled(),
                        "endToEndThresholdMillis", analysis.getSlowQuery().getEndToEndThresholdMillis(),
                        "maxTrackedKeys", analysis.getSlowQuery().getMaxTrackedKeys(),
                        "windowSeconds", analysis.getSlowQuery().getWindowSeconds()));
    }

    private static Map<String, Object> governance(ProxyConfig.Governance governance) {
        return map(
                "commandPolicy", map(
                        "deniedCommands", strings(governance.getCommandPolicy().getDeniedCommands()),
                        "warnOnlyCommands", strings(governance.getCommandPolicy().getWarnOnlyCommands())),
                "enabled", governance.isEnabled(),
                "keyLimitBucketMillis", governance.getKeyLimitBucketMillis(),
                "keyLimitWindowMillis", governance.getKeyLimitWindowMillis(),
                "namespaces", namespaces(governance.getNamespaces()),
                "requireAuth", governance.isRequireAuth());
    }

    private static List<Object> namespaces(List<ProxyConfig.Namespace> namespaces) {
        List<Object> out = new ArrayList<>();
        for (ProxyConfig.Namespace namespace : list(namespaces)) {
            out.add(map(
                    "allowedKeyPrefixes", strings(namespace.getAllowedKeyPrefixes()),
                    "deniedCommands", strings(namespace.getDeniedCommands()),
                    "disabledKeys", strings(namespace.getDisabledKeys()),
                    "keyRules", keyRules(namespace.getKeyRules()),
                    "limits", map(
                            "maxConnections", namespace.getLimits().getMaxConnections(),
                            "maxInflight", namespace.getLimits().getMaxInflight(),
                            "maxQps", namespace.getLimits().getMaxQps()),
                    "name", text(namespace.getName()),
                    "readOnly", namespace.isReadOnly(),
                    "token", text(namespace.getToken()),
                    "warnOnlyCommands", strings(namespace.getWarnOnlyCommands())));
        }
        return out;
    }

    private static List<Object> keyRules(List<ProxyConfig.KeyRule> rules) {
        List<Object> out = new ArrayList<>();
        for (ProxyConfig.KeyRule rule : list(rules)) {
            out.add(map(
                    "disabled", rule.isDisabled(),
                    "hashTag", text(rule.getHashTag()),
                    "keyPrefix", text(rule.getKeyPrefix()),
                    "maxQps", rule.getMaxQps(),
                    "name", text(rule.getName())));
        }
        return out;
    }

    private static TreeMap<String, Object> map(Object... kv) {
        TreeMap<String, Object> out = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
