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
	}
}
