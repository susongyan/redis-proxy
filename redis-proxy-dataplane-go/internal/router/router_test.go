package router

import (
	"net"
	"testing"

	"github.com/example/redis-proxy-dataplane-go/internal/backend"
	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
	"go.uber.org/zap"
)

func TestSlotExamples(t *testing.T) {
	tests := map[string]int{
		"123456789": 12739,
		"foo":       12182,
		"{user}:1":  5474,
		"{user}:2":  5474,
	}
	for key, want := range tests {
		if got := Slot([]byte(key)); got != want {
			t.Fatalf("slot(%q)=%d want %d", key, got, want)
		}
	}
}

func TestParseClusterSlots(t *testing.T) {
	rt := &Router{
		mode:           "cluster",
		defaultCluster: "redis-a",
		clusters: map[string]config.ClusterConfig{
			"redis-a": {Name: "redis-a", Nodes: []string{"127.0.0.1:7000", "127.0.0.1:7001"}},
		},
		states: map[string]*clusterState{"redis-a": {}},
	}
	raw := []byte("*2\r\n" +
		"*3\r\n:0\r\n:8191\r\n*2\r\n$10\r\n172.18.0.2\r\n:7000\r\n" +
		"*3\r\n:8192\r\n:16383\r\n*2\r\n$10\r\n172.18.0.3\r\n:7001\r\n")
	slots, err := rt.parseClusterSlots(raw)
	if err != nil {
		t.Fatal(err)
	}
	if got := slots[0]; got != "127.0.0.1:7000" {
		t.Fatalf("slot 0 addr=%q", got)
	}
	if got := slots[8192]; got != "127.0.0.1:7001" {
		t.Fatalf("slot 8192 addr=%q", got)
	}
	req := protocolRequest("GET", "foo")
	addr, err := rt.Route(req)
	if err != nil {
		t.Fatal(err)
	}
	if addr == "" {
		t.Fatal("route returned empty addr")
	}
}

func TestUpdateMoved(t *testing.T) {
	rt := &Router{
		mode:           "cluster",
		defaultCluster: "redis-a",
		clusters: map[string]config.ClusterConfig{
			"redis-a": {Name: "redis-a", Nodes: []string{"127.0.0.1:7000"}},
		},
		states: map[string]*clusterState{"redis-a": {}},
	}
	rt.UpdateMoved([]byte("-MOVED 42 172.18.0.2:7000\r\n"), nil)
	addr, ok := rt.slotAddr("redis-a", 42)
	if !ok || addr != "127.0.0.1:7000" {
		t.Fatalf("slot 42 addr=%q ok=%v", addr, ok)
	}
	if got := rt.SlotCoverage(); got != 1 {
		t.Fatalf("slot coverage=%d want 1", got)
	}
}

func TestAskDoesNotUpdateSlotCache(t *testing.T) {
	rt := &Router{
		mode:           "cluster",
		defaultCluster: "redis-a",
		clusters: map[string]config.ClusterConfig{
			"redis-a": {Name: "redis-a", Nodes: []string{"127.0.0.1:7000"}},
		},
		states: map[string]*clusterState{"redis-a": {}},
	}
	rt.UpdateMoved([]byte("-ASK 42 172.18.0.2:7000\r\n"), nil)
	if _, ok := rt.slotAddr("redis-a", 42); ok {
		t.Fatal("ASK must not update long-lived slot cache")
	}
}

func TestNormalizeAddrMapsClusterContainerHostnameByPort(t *testing.T) {
	rt := &Router{
		mode:           "cluster",
		defaultCluster: "redis-a",
		clusters: map[string]config.ClusterConfig{
			"redis-a": {Name: "redis-a", Nodes: []string{"127.0.0.1:7100", "127.0.0.1:7101"}},
		},
		states: map[string]*clusterState{"redis-a": {}},
	}
	if got := rt.normalizeAddr("redis-a", "redis-proxy-cluster-7101:7101"); got != "127.0.0.1:7101" {
		t.Fatalf("normalized addr=%q", got)
	}
}

func TestRouteRuleSelectsGrayClusterByPrefix(t *testing.T) {
	rt, err := New(&config.Config{
		Mode: "standalone",
		Backends: config.BackendConfig{Clusters: []config.ClusterConfig{
			{Name: "redis-a", Nodes: []string{"127.0.0.1:6379"}},
			{Name: "redis-b", Nodes: []string{"127.0.0.1:6380"}},
		}},
		Routing: config.RoutingConfig{
			DefaultCluster: "redis-a",
			Rules: []config.RouteRuleConfig{{
				Name:           "gray-user",
				Cluster:        "redis-b",
				KeyPrefix:      "user:",
				TrafficPercent: 100,
			}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}

	addr, err := rt.Route(protocolRequest("GET", "user:1"))
	if err != nil {
		t.Fatal(err)
	}
	if addr != "127.0.0.1:6380" {
		t.Fatalf("gray route addr=%q", addr)
	}

	addr, err = rt.Route(protocolRequest("GET", "order:1"))
	if err != nil {
		t.Fatal(err)
	}
	if addr != "127.0.0.1:6379" {
		t.Fatalf("default route addr=%q", addr)
	}
}

func TestManagerApplyConfigAcceptsHigherEpochAndRejectsStale(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go acceptLoop(ln)

	addr := ln.Addr().String()
	base := managerTestConfig(addr, 1, nil)
	pools, err := backend.NewPools(base, metrics.NewRegistry(), zap.NewNop())
	if err != nil {
		t.Fatal(err)
	}
	defer pools.Close()

	manager, err := NewManager(base)
	if err != nil {
		t.Fatal(err)
	}

	stale := managerTestConfig(addr, 1, nil)
	result, err := manager.ApplyConfig(stale, pools)
	if err == nil || result != "stale_epoch" {
		t.Fatalf("stale apply result=%q err=%v", result, err)
	}

	next := managerTestConfig(addr, 2, []config.RouteRuleConfig{{
		Name:           "gray-user",
		Cluster:        "redis-b",
		KeyPrefix:      "user:",
		TrafficPercent: 100,
	}})
	result, err = manager.ApplyConfig(next, pools)
	if err != nil || result != "success" {
		t.Fatalf("apply result=%q err=%v", result, err)
	}
	if manager.CurrentEpoch() != 2 {
		t.Fatalf("epoch=%d want 2", manager.CurrentEpoch())
	}
	decision, err := manager.RouteDecision(protocolRequest("GET", "user:1"))
	if err != nil {
		t.Fatal(err)
	}
	if decision.Cluster != "redis-b" || decision.Rule != "gray-user" {
		t.Fatalf("decision=%+v", decision)
	}
}

func acceptLoop(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer conn.Close()
			select {}
		}()
	}
}

func managerTestConfig(addr string, epoch int64, rules []config.RouteRuleConfig) *config.Config {
	return &config.Config{
		Server: config.ServerConfig{Listen: "127.0.0.1:6379"},
		Admin:  config.AdminConfig{Listen: "127.0.0.1:8080"},
		Mode:   "standalone",
		Backends: config.BackendConfig{Clusters: []config.ClusterConfig{
			{Name: "redis-a", Nodes: []string{addr}},
			{Name: "redis-b", Nodes: []string{addr}},
		}},
		Routing: config.RoutingConfig{
			DefaultCluster: "redis-a",
			RouteEpoch:     epoch,
			Rules:          rules,
		},
		Limits: config.LimitsConfig{
			MaxPipelineDepth: 1024,
			MaxRequestBytes:  1024,
			MaxResponseBytes: 1024,
		},
	}
}

func protocolRequest(args ...string) protocol.Request {
	values := make([][]byte, 0, len(args))
	for _, arg := range args {
		values = append(values, []byte(arg))
	}
	return protocol.Request{Args: values}
}
