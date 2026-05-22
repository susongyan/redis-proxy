package proxy

import (
	"testing"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/router"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestObserveResponseSize(t *testing.T) {
	reg := metrics.NewRegistry()
	cfg := &config.Config{
		Mode: "standalone",
		Backends: config.BackendConfig{Clusters: []config.ClusterConfig{{
			Name:  "redis-a",
			Nodes: []string{"127.0.0.1:6379"},
		}}},
		Routing: config.RoutingConfig{DefaultCluster: "redis-a"},
		Limits:  config.LimitsConfig{LargeResponseBytes: 4},
	}
	config.ApplyDefaults(cfg)
	manager, err := router.NewManager(cfg)
	if err != nil {
		t.Fatal(err)
	}
	server := &Server{
		cfg:     cfg,
		router:  manager,
		metrics: reg,
	}

	server.observeResponseSize("GET", 3)
	server.observeResponseSize("GET", 4)

	if got := testutil.ToFloat64(reg.LargeResponses.WithLabelValues("GET")); got != 1 {
		t.Fatalf("large response count=%v want 1", got)
	}
}
