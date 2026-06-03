package config

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
)

func SnapshotHash(cfg *Config) string {
	payload := map[string]any{
		"analysis": map[string]any{
			"hotKey": map[string]any{
				"bucketMillis":   cfg.Analysis.HotKey.BucketMillis,
				"enabled":        cfg.Analysis.HotKey.IsEnabled(),
				"maxTrackedKeys": cfg.Analysis.HotKey.MaxTrackedKeys,
				"metricsTopN":    cfg.Analysis.HotKey.MetricsTopN,
				"windowSeconds":  cfg.Analysis.HotKey.WindowSeconds,
			},
			"largeKey": map[string]any{
				"bucketMillis":           cfg.Analysis.LargeKey.BucketMillis,
				"debugTopN":              cfg.Analysis.LargeKey.DebugTopN,
				"enabled":                cfg.Analysis.LargeKey.IsEnabled(),
				"maxTrackedKeys":         cfg.Analysis.LargeKey.MaxTrackedKeys,
				"requestBytesThreshold":  cfg.Analysis.LargeKey.RequestBytesThreshold,
				"responseBytesThreshold": cfg.Analysis.LargeKey.ResponseBytesThreshold,
				"windowSeconds":          cfg.Analysis.LargeKey.WindowSeconds,
			},
			"slowQuery": map[string]any{
				"backendThresholdMillis":  cfg.Analysis.SlowQuery.BackendThresholdMillis,
				"bucketMillis":            cfg.Analysis.SlowQuery.BucketMillis,
				"debugTopN":               cfg.Analysis.SlowQuery.DebugTopN,
				"enabled":                 cfg.Analysis.SlowQuery.IsEnabled(),
				"endToEndThresholdMillis": cfg.Analysis.SlowQuery.EndToEndThresholdMillis,
				"maxTrackedKeys":          cfg.Analysis.SlowQuery.MaxTrackedKeys,
				"windowSeconds":           cfg.Analysis.SlowQuery.WindowSeconds,
			},
		},
		"backends": map[string]any{"clusters": clustersHash(cfg.Backends.Clusters)},
		"governance": map[string]any{
			"commandPolicy": map[string]any{
				"deniedCommands":   nonNilStrings(cfg.Governance.CommandPolicy.DeniedCommands),
				"warnOnlyCommands": nonNilStrings(cfg.Governance.CommandPolicy.WarnOnlyCommands),
			},
			"enabled":              cfg.Governance.Enabled,
			"keyLimitBucketMillis": cfg.Governance.KeyLimitBucketMillis,
			"keyLimitWindowMillis": cfg.Governance.KeyLimitWindowMillis,
			"namespaces":           namespacesHash(cfg.Governance.Namespaces),
			"requireAuth":          cfg.Governance.RequireAuth,
		},
		"limits": map[string]any{
			"largeResponseBytes":          cfg.Limits.LargeResponseBytes,
			"maxPipelineDepth":            cfg.Limits.MaxPipelineDepth,
			"maxRequestBytes":             cfg.Limits.MaxRequestBytes,
			"maxResponseBytes":            cfg.Limits.MaxResponseBytes,
			"pipelineFlushBatchSize":      cfg.Limits.PipelineFlushBatchSize,
			"pipelineFlushMaxDelayMillis": cfg.Limits.PipelineFlushMaxDelayMillis,
		},
		"mode": cfg.Mode,
		"routing": map[string]any{
			"clusterSlotsRefreshIntervalSeconds": cfg.Routing.ClusterSlotsRefreshIntervalSeconds,
			"backendAffinityStrategy":            cfg.Routing.BackendAffinityStrategy,
			"defaultCluster":                     cfg.Routing.DefaultCluster,
			"routeEpoch":                         cfg.Routing.RouteEpoch,
			"rules":                              rulesHash(cfg.Routing.Rules),
		},
	}
	raw, _ := json.Marshal(payload)
	sum := sha256.Sum256(raw)
	return "sha256:" + hex.EncodeToString(sum[:])
}

func clustersHash(clusters []ClusterConfig) []any {
	out := make([]any, 0, len(clusters))
	for _, cluster := range clusters {
		out = append(out, map[string]any{
			"auth": map[string]any{
				"enabled":  cluster.Auth.Enabled,
				"password": cluster.Auth.Password,
				"username": cluster.Auth.Username,
			},
			"name":  cluster.Name,
			"nodes": nonNilStrings(cluster.Nodes),
			"pool": map[string]any{
				"connectionsPerNode":       cluster.Pool.ConnectionsPerNode,
				"maxInflightPerConnection": cluster.Pool.MaxInflightPerConnection,
			},
		})
	}
	return out
}

func rulesHash(rules []RouteRuleConfig) []any {
	out := make([]any, 0, len(rules))
	for _, rule := range rules {
		out = append(out, map[string]any{
			"cluster":        rule.Cluster,
			"hashTag":        rule.HashTag,
			"keyPattern":     rule.KeyPattern,
			"keyPrefix":      rule.KeyPrefix,
			"matchAll":       rule.MatchAll,
			"name":           rule.Name,
			"namespace":      rule.Namespace,
			"trafficPercent": rule.TrafficPercent,
		})
	}
	return out
}

func namespacesHash(namespaces []NamespaceConfig) []any {
	out := make([]any, 0, len(namespaces))
	for _, namespace := range namespaces {
		out = append(out, map[string]any{
			"allowedKeyPrefixes": nonNilStrings(namespace.AllowedKeyPrefixes),
			"deniedCommands":     nonNilStrings(namespace.DeniedCommands),
			"disabledKeys":       nonNilStrings(namespace.DisabledKeys),
			"keyRules":           keyRulesHash(namespace.KeyRules),
			"limits": map[string]any{
				"maxConnections": namespace.Limits.MaxConnections,
				"maxInflight":    namespace.Limits.MaxInflight,
				"maxQps":         namespace.Limits.MaxQPS,
			},
			"name":             namespace.Name,
			"readOnly":         namespace.ReadOnly,
			"token":            namespace.Token,
			"warnOnlyCommands": nonNilStrings(namespace.WarnOnlyCommands),
		})
	}
	return out
}

func keyRulesHash(rules []KeyRuleConfig) []any {
	out := make([]any, 0, len(rules))
	for _, rule := range rules {
		out = append(out, map[string]any{
			"disabled":  rule.Disabled,
			"hashTag":   rule.HashTag,
			"keyPrefix": rule.KeyPrefix,
			"maxQps":    rule.MaxQPS,
			"name":      rule.Name,
		})
	}
	return out
}

func nonNilStrings(values []string) []string {
	if values == nil {
		return []string{}
	}
	return values
}
