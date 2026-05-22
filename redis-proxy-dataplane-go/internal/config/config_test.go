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
	cfg.Routing.Rules = []RouteRuleConfig{{
		Name:           "gray",
		Cluster:        "redis-b",
		HashTag:        "tenant-a",
		TrafficPercent: 25,
	}}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
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

func validConfig() Config {
	return Config{
		Server: ServerConfig{Listen: "127.0.0.1:6379"},
		Admin:  AdminConfig{Listen: "127.0.0.1:8080"},
		Mode:   "cluster",
		Backends: BackendConfig{Clusters: []ClusterConfig{{
			Name:  "redis-a",
			Nodes: []string{"127.0.0.1:7000"},
		}}},
		Routing: RoutingConfig{DefaultCluster: "redis-a"},
		Limits:  LimitsConfig{MaxPipelineDepth: 1024, MaxRequestBytes: 1024, MaxResponseBytes: 1024, LargeResponseBytes: 512},
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
		}},
	}
}
