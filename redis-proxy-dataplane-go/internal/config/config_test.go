package config

import "testing"

func TestApplyDefaultsLeavesClusterSlotRefreshIntervalDisabled(t *testing.T) {
	cfg := Config{}
	applyDefaults(&cfg)
	if cfg.Routing.ClusterSlotsRefreshIntervalSeconds != 0 {
		t.Fatalf("clusterSlotsRefreshIntervalSeconds=%d want 0", cfg.Routing.ClusterSlotsRefreshIntervalSeconds)
	}
	if cfg.Limits.LargeResponseBytes != 1024*1024 {
		t.Fatalf("largeResponseBytes=%d want 1048576", cfg.Limits.LargeResponseBytes)
	}
	if cfg.Limits.PipelineFlushBatchSize != 16 || cfg.Limits.PipelineFlushMaxDelayMillis != 1 {
		t.Fatalf("pipeline flush defaults=%+v", cfg.Limits)
	}
	if cfg.Routing.BackendAffinityStrategy != "client" {
		t.Fatalf("backendAffinityStrategy=%q want client", cfg.Routing.BackendAffinityStrategy)
	}
	if !cfg.Analysis.HotKey.IsEnabled() {
		t.Fatal("hotKey.enabled=false want true by default")
	}
	if cfg.Analysis.HotKey.WindowSeconds != 60 || cfg.Analysis.HotKey.BucketMillis != 1000 ||
		cfg.Analysis.HotKey.MaxTrackedKeys != 10000 || cfg.Analysis.HotKey.MetricsTopN != 20 {
		t.Fatalf("hotKey defaults=%+v", cfg.Analysis.HotKey)
	}
	if !cfg.Analysis.LargeKey.IsEnabled() {
		t.Fatal("largeKey.enabled=false want true by default")
	}
	if cfg.Analysis.LargeKey.RequestBytesThreshold != 1024*1024 ||
		cfg.Analysis.LargeKey.ResponseBytesThreshold != 1024*1024 ||
		cfg.Analysis.LargeKey.WindowSeconds != 300 ||
		cfg.Analysis.LargeKey.BucketMillis != 1000 ||
		cfg.Analysis.LargeKey.MaxTrackedKeys != 10000 ||
		cfg.Analysis.LargeKey.DebugTopN != 100 {
		t.Fatalf("largeKey defaults=%+v", cfg.Analysis.LargeKey)
	}
	if !cfg.Analysis.SlowQuery.IsEnabled() {
		t.Fatal("slowQuery.enabled=false want true by default")
	}
	if cfg.Analysis.SlowQuery.EndToEndThresholdMillis != 100 ||
		cfg.Analysis.SlowQuery.BackendThresholdMillis != 50 ||
		cfg.Analysis.SlowQuery.WindowSeconds != 300 ||
		cfg.Analysis.SlowQuery.BucketMillis != 1000 ||
		cfg.Analysis.SlowQuery.MaxTrackedKeys != 10000 ||
		cfg.Analysis.SlowQuery.DebugTopN != 100 {
		t.Fatalf("slowQuery defaults=%+v", cfg.Analysis.SlowQuery)
	}
}

func TestValidateRejectsNegativeClusterSlotRefreshInterval(t *testing.T) {
	cfg := Config{
		Server: ServerConfig{Listen: "127.0.0.1:6379"},
		Admin:  AdminConfig{Listen: "127.0.0.1:8080"},
		Mode:   "cluster",
		Backends: BackendConfig{Clusters: []ClusterConfig{{
			Name:  "redis-a",
			Nodes: []string{"127.0.0.1:7000"},
		}}},
		Routing: RoutingConfig{
			DefaultCluster:                     "redis-a",
			ClusterSlotsRefreshIntervalSeconds: -1,
		},
	}
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected validation error")
	}
}

func TestValidateRejectsRouteRuleUnknownCluster(t *testing.T) {
	cfg := validConfig()
	cfg.Routing.Rules = []RouteRuleConfig{{
		Name:           "bad",
		Cluster:        "missing",
		KeyPrefix:      "user:",
		TrafficPercent: 10,
	}}
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected validation error")
	}
}

func TestValidateAcceptsRouteRule(t *testing.T) {
	cfg := validConfig()
	cfg.Backends.Clusters = append(cfg.Backends.Clusters, ClusterConfig{Name: "redis-b", Nodes: []string{"127.0.0.1:7001"}})
	cfg.Governance.Namespaces = []NamespaceConfig{{Name: "app-a", Token: "token-a"}}
	cfg.Routing.Rules = []RouteRuleConfig{{
		Name:           "gray",
		Cluster:        "redis-b",
		Namespace:      "app-a",
		KeyPattern:     "user:*:profile",
		HashTag:        "tenant-a",
		TrafficPercent: 25,
	}}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
}

func TestValidateRejectsRouteRuleUnknownNamespace(t *testing.T) {
	cfg := validConfig()
	cfg.Backends.Clusters = append(cfg.Backends.Clusters, ClusterConfig{Name: "redis-b", Nodes: []string{"127.0.0.1:7001"}})
	cfg.Routing.Rules = []RouteRuleConfig{{
		Name:           "gray",
		Cluster:        "redis-b",
		Namespace:      "missing",
		TrafficPercent: 25,
	}}
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected unknown namespace validation error")
	}
}

func TestValidateGovernance(t *testing.T) {
	cfg := validConfig()
	cfg.Governance.Enabled = true
	cfg.Governance.Namespaces = []NamespaceConfig{{Name: "app-a", Token: "token-a"}}
	cfg.Governance.CommandPolicy.DeniedCommands = []string{"FLUSHALL"}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}

	cfg.Governance.Namespaces = append(cfg.Governance.Namespaces, NamespaceConfig{Name: "app-a", Token: "other"})
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected duplicate namespace validation error")
	}
}

func TestValidateRejectsInvalidHotKeyAnalysis(t *testing.T) {
	cfg := validConfig()
	cfg.Analysis.HotKey.WindowSeconds = 60
	cfg.Analysis.HotKey.BucketMillis = 700
	cfg.Analysis.HotKey.MaxTrackedKeys = 10000
	cfg.Analysis.HotKey.MetricsTopN = 20
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected invalid hot key bucket validation error")
	}
}

func TestValidateRejectsInvalidLargeKeyAnalysis(t *testing.T) {
	cfg := validConfig()
	cfg.Analysis.LargeKey.WindowSeconds = 300
	cfg.Analysis.LargeKey.BucketMillis = 700
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected invalid large key bucket validation error")
	}
}

func TestValidateRejectsInvalidSlowQueryAnalysis(t *testing.T) {
	cfg := validConfig()
	cfg.Analysis.SlowQuery.WindowSeconds = 300
	cfg.Analysis.SlowQuery.BucketMillis = 700
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected invalid slow query bucket validation error")
	}
}

func TestSnapshotHashIgnoresLocalRuntimeFields(t *testing.T) {
	cfg := validConfig()
	ApplyDefaults(&cfg)
	base := SnapshotHash(&cfg)

	cfg.Instance.ProxyID = "proxy-2"
	cfg.Server.Listen = "127.0.0.1:9999"
	cfg.Admin.Listen = "127.0.0.1:19999"
	cfg.ControlPlane.Enabled = true
	cfg.ControlPlane.URL = "http://127.0.0.1:8090/api/v1/config"
	if got := SnapshotHash(&cfg); got != base {
		t.Fatalf("local runtime fields changed hash: %s -> %s", base, got)
	}

	cfg.Routing.RouteEpoch++
	if got := SnapshotHash(&cfg); got == base {
		t.Fatal("routing change did not change snapshot hash")
	}
}

func TestSnapshotHashChangesForGovernanceLimitsAndAnalysis(t *testing.T) {
	cfg := validConfig()
	ApplyDefaults(&cfg)
	base := SnapshotHash(&cfg)

	cfg.Governance.Enabled = true
	cfg.Governance.RequireAuth = true
	cfg.Governance.Namespaces = []NamespaceConfig{{Name: "app-a", Token: "token-a"}}
	ApplyDefaults(&cfg)
	if got := SnapshotHash(&cfg); got == base {
		t.Fatal("governance change did not change snapshot hash")
	}

	base = SnapshotHash(&cfg)
	cfg.Limits.LargeResponseBytes++
	if got := SnapshotHash(&cfg); got == base {
		t.Fatal("limits change did not change snapshot hash")
	}

	base = SnapshotHash(&cfg)
	cfg.Limits.PipelineFlushBatchSize++
	if got := SnapshotHash(&cfg); got == base {
		t.Fatal("pipeline flush limit change did not change snapshot hash")
	}

	base = SnapshotHash(&cfg)
	cfg.Routing.BackendAffinityStrategy = "keySlot"
	if got := SnapshotHash(&cfg); got == base {
		t.Fatal("backend affinity change did not change snapshot hash")
	}

	base = SnapshotHash(&cfg)
	cfg.Analysis.HotKey.MetricsTopN++
	if got := SnapshotHash(&cfg); got == base {
		t.Fatal("analysis change did not change snapshot hash")
	}
}

func TestValidateRejectsBackendAuthWithoutPassword(t *testing.T) {
	cfg := validConfig()
	cfg.Backends.Clusters[0].Auth.Enabled = true
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected backend auth password validation error")
	}
}

func TestSnapshotHashChangesForBackendAuth(t *testing.T) {
	cfg := validConfig()
	ApplyDefaults(&cfg)
	base := SnapshotHash(&cfg)
	cfg.Backends.Clusters[0].Auth.Enabled = true
	cfg.Backends.Clusters[0].Auth.Username = "default"
	cfg.Backends.Clusters[0].Auth.Password = "secret"
	if got := SnapshotHash(&cfg); got == base {
		t.Fatal("backend auth change did not change snapshot hash")
	}
}

func validConfig() Config {
	return Config{
		Server: ServerConfig{Listen: "127.0.0.1:6379"},
		Admin:  AdminConfig{Listen: "127.0.0.1:8080"},
		Mode:   "cluster",
		Backends: BackendConfig{Clusters: []ClusterConfig{{
			Name:  "redis-a",
			Nodes: []string{"127.0.0.1:7000"},
		}}},
		Routing: RoutingConfig{DefaultCluster: "redis-a", BackendAffinityStrategy: "client"},
		Limits:  LimitsConfig{MaxPipelineDepth: 1024, PipelineFlushBatchSize: 16, PipelineFlushMaxDelayMillis: 1, MaxRequestBytes: 1024, MaxResponseBytes: 1024, LargeResponseBytes: 512},
		Analysis: AnalysisConfig{HotKey: HotKeyAnalysisConfig{
			WindowSeconds:  60,
			BucketMillis:   1000,
			MaxTrackedKeys: 10000,
			MetricsTopN:    20,
		}, LargeKey: LargeKeyAnalysisConfig{
			RequestBytesThreshold:  1024,
			ResponseBytesThreshold: 1024,
			WindowSeconds:          300,
			BucketMillis:           1000,
			MaxTrackedKeys:         10000,
			DebugTopN:              100,
		}, SlowQuery: SlowQueryAnalysisConfig{
			EndToEndThresholdMillis: 100,
			BackendThresholdMillis:  50,
			WindowSeconds:           300,
			BucketMillis:            1000,
			MaxTrackedKeys:          10000,
			DebugTopN:               100,
		}},
	}
}
