package com.zuomagai.redisproxy.dataplane.router;

import com.zuomagai.redisproxy.dataplane.config.ProxyProperties;
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

    static String hash(ProxyProperties config) {
        try {
            byte[] raw = MAPPER.writeValueAsBytes(canonical(config));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("compute route config hash", e);
        }
    }

    private static Map<String, Object> canonical(ProxyProperties c) {
        TreeMap<String, Object> root = map();
        root.put("analysis", analysis(c.getAnalysis()));
        root.put("backends", map("clusters", clusters(c.getBackends().getClusters())));
        root.put("governance", governance(c.getGovernance()));
        root.put("limits", map(
                "largeResponseBytes", c.getLimits().getLargeResponseBytes(),
                "maxPipelineDepth", c.getLimits().getMaxPipelineDepth(),
                "maxRequestBytes", c.getLimits().getMaxRequestBytes(),
                "maxResponseBytes", c.getLimits().getMaxResponseBytes(),
                "pipelineFlushBatchSize", c.getLimits().getPipelineFlushBatchSize(),
                "pipelineFlushMaxDelayMillis", c.getLimits().getPipelineFlushMaxDelayMillis()));
        root.put("mode", text(c.getMode()));
        root.put("routing", routing(c.getRouting()));
        return root;
    }

    private static Map<String, Object> routing(ProxyProperties.Routing routing) {
        return map(
                "clusterSlotsRefreshIntervalSeconds", routing.getClusterSlotsRefreshIntervalSeconds(),
                "backendAffinityStrategy", text(routing.getBackendAffinityStrategy()),
                "defaultCluster", text(routing.getDefaultCluster()),
                "routeEpoch", routing.getRouteEpoch(),
                "rules", routeRules(routing.getRules()));
    }

    private static List<Object> routeRules(List<ProxyProperties.RouteRule> rules) {
        List<Object> out = new ArrayList<>();
        for (ProxyProperties.RouteRule rule : list(rules)) {
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

    private static List<Object> clusters(List<ProxyProperties.Cluster> clusters) {
        List<Object> out = new ArrayList<>();
        for (ProxyProperties.Cluster cluster : list(clusters)) {
            out.add(map(
                    "name", text(cluster.getName()),
                    "nodes", strings(cluster.getNodes()),
                    "pool", map(
                            "connectionsPerNode", cluster.getPool().getConnectionsPerNode(),
                            "maxInflightPerConnection", cluster.getPool().getMaxInflightPerConnection())));
        }
        return out;
    }

    private static Map<String, Object> analysis(ProxyProperties.Analysis analysis) {
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

    private static Map<String, Object> governance(ProxyProperties.Governance governance) {
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

    private static List<Object> namespaces(List<ProxyProperties.Namespace> namespaces) {
        List<Object> out = new ArrayList<>();
        for (ProxyProperties.Namespace namespace : list(namespaces)) {
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

    private static List<Object> keyRules(List<ProxyProperties.KeyRule> rules) {
        List<Object> out = new ArrayList<>();
        for (ProxyProperties.KeyRule rule : list(rules)) {
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
        TreeMap<String, Object> out = map();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static TreeMap<String, Object> map() {
        return new TreeMap<>();
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
