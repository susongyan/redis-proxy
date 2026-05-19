package config

import "testing"

func TestApplyDefaultsLeavesClusterSlotRefreshIntervalDisabled(t *testing.T) {
	cfg := Config{}
	applyDefaults(&cfg)
	if cfg.Routing.ClusterSlotsRefreshIntervalSeconds != 0 {
		t.Fatalf("clusterSlotsRefreshIntervalSeconds=%d want 0", cfg.Routing.ClusterSlotsRefreshIntervalSeconds)
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
