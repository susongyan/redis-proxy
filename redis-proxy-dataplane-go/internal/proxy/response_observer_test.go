package proxy

import (
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/analysis"
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

func TestRequestAffinityStrategies(t *testing.T) {
	cfg := &config.Config{
		Mode: "standalone",
		Backends: config.BackendConfig{Clusters: []config.ClusterConfig{{
			Name:  "redis-a",
			Nodes: []string{"127.0.0.1:6379"},
		}}},
		Routing: config.RoutingConfig{DefaultCluster: "redis-a", BackendAffinityStrategy: "keySlot"},
		Limits:  config.LimitsConfig{MaxPipelineDepth: 1024, PipelineFlushBatchSize: 16, PipelineFlushMaxDelayMillis: 1, MaxRequestBytes: 1024, MaxResponseBytes: 1024, LargeResponseBytes: 512},
	}
	config.ApplyDefaults(cfg)
	manager, err := router.NewManager(cfg)
	if err != nil {
		t.Fatal(err)
	}
	server := &Server{router: manager}
	if got := server.requestAffinity(99, request("GET", "{user}:1")); got != uint64(router.Slot([]byte("{user}:1"))) {
		t.Fatalf("keySlot affinity=%d", got)
	}

	cfg.Routing.BackendAffinityStrategy = "client"
	manager, err = router.NewManager(cfg)
	if err != nil {
		t.Fatal(err)
	}
	server.router = manager
	if got := server.requestAffinity(99, request("GET", "{user}:1")); got != 99 {
		t.Fatalf("client affinity=%d want 99", got)
	}
}

func TestWriteBatchCoalescesResponsesInOrder(t *testing.T) {
	reg := metrics.NewRegistry()
	cfg := &config.Config{
		Mode: "standalone",
		Backends: config.BackendConfig{Clusters: []config.ClusterConfig{{
			Name:  "redis-a",
			Nodes: []string{"127.0.0.1:6379"},
		}}},
		Routing: config.RoutingConfig{DefaultCluster: "redis-a"},
		Limits:  config.LimitsConfig{MaxPipelineDepth: 1024, PipelineFlushBatchSize: 2, PipelineFlushMaxDelayMillis: 1, MaxRequestBytes: 1024, MaxResponseBytes: 1024, LargeResponseBytes: 512},
	}
	config.ApplyDefaults(cfg)
	manager, err := router.NewManager(cfg)
	if err != nil {
		t.Fatal(err)
	}
	server := &Server{
		cfg:         cfg,
		router:      manager,
		metrics:     reg,
		largeKeys:   analysis.NewLargeKeyTracker(reg, cfg.Analysis.LargeKey),
		slowQueries: analysis.NewSlowQueryTracker(reg, cfg.Analysis.SlowQuery),
	}
	client, proxyConn := net.Pipe()
	defer client.Close()
	defer proxyConn.Close()
	var pending atomic.Int64
	pending.Store(2)
	reg.ClientPending.Add(2)
	done := make(chan string, 1)
	go func() {
		buf := make([]byte, len("+OK\r\n+PONG\r\n"))
		_, _ = client.Read(buf)
		done <- string(buf)
	}()
	if !server.writeBatch(proxyConn, []completion{
		{seq: 0, command: "GET", response: []byte("+OK\r\n"), start: time.Now(), completedAt: time.Now()},
		{seq: 1, command: "PING", response: []byte("+PONG\r\n"), start: time.Now(), completedAt: time.Now()},
	}, &pending) {
		t.Fatal("writeBatch failed")
	}
	select {
	case got := <-done:
		if got != "+OK\r\n+PONG\r\n" {
			t.Fatalf("batch response=%q", got)
		}
	case <-time.After(time.Second):
		t.Fatal("timeout reading batch")
	}
	if pending.Load() != 0 {
		t.Fatalf("pending=%d want 0", pending.Load())
	}
}
