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

func TestAskTargetNormalizesWithinOriginalCluster(t *testing.T) {
	rt := &Router{
		mode:           "cluster",
		defaultCluster: "redis-a",
		clusters: map[string]config.ClusterConfig{
			"redis-a": {Name: "redis-a", Nodes: []string{"127.0.0.1:7000"}},
			"redis-b": {Name: "redis-b", Nodes: []string{"127.0.0.1:7100"}},
		},
		states: map[string]*clusterState{"redis-a": {}, "redis-b": {}},
	}
	addr, err := rt.AskTarget([]byte("-ASK 42 redis-proxy-cluster-7100:7100\r\n"), "redis-b", nil)
	if err != nil {
		t.Fatal(err)
	}
	if addr != "127.0.0.1:7100" {
		t.Fatalf("ask target=%q", addr)
	}
	if got := rt.ClusterSlotCoverage("redis-b"); got != 0 {
		t.Fatalf("ASK polluted slot cache, coverage=%d", got)
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

func TestMatchAllRuleSelectsClusterAndAffectsHash(t *testing.T) {
	cfg := &config.Config{
		Mode: "standalone",
		Backends: config.BackendConfig{Clusters: []config.ClusterConfig{
			{Name: "redis-a", Nodes: []string{"127.0.0.1:6379"}},
			{Name: "redis-b", Nodes: []string{"127.0.0.1:6380"}},
		}},
		Routing: config.RoutingConfig{
			DefaultCluster: "redis-a",
			Rules: []config.RouteRuleConfig{{
				Name:           "cluster-switch-1",
				Cluster:        "redis-b",
				MatchAll:       true,
				TrafficPercent: 100,
			}},
		},
	}
	baseHash := config.SnapshotHash(cfg)
	rt, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	addr, err := rt.Route(protocolRequest("GET", "order:1"))
	if err != nil {
		t.Fatal(err)
	}
	if addr != "127.0.0.1:6380" {
		t.Fatalf("matchAll route addr=%q", addr)
	}
	cfg.Routing.Rules[0].MatchAll = false
	if got := config.SnapshotHash(cfg); got == baseHash {
		t.Fatal("matchAll change must affect snapshot hash")
	}
}

func TestRouteRuleSelectsClusterByNamespacePatternAndHashTag(t *testing.T) {
	rt, err := New(&config.Config{
		Mode: "standalone",
		Backends: config.BackendConfig{Clusters: []config.ClusterConfig{
			{Name: "redis-a", Nodes: []string{"127.0.0.1:6379"}},
			{Name: "redis-b", Nodes: []string{"127.0.0.1:6380"}},
			{Name: "redis-c", Nodes: []string{"127.0.0.1:6381"}},
		}},
		Routing: config.RoutingConfig{
			DefaultCluster: "redis-a",
			Rules: []config.RouteRuleConfig{
				{Name: "app-profile", Cluster: "redis-b", Namespace: "app-a", KeyPattern: "user:*:profile", TrafficPercent: 100},
				{Name: "order-tag", Cluster: "redis-c", HashTag: "order", TrafficPercent: 100},
			},
		},
		Governance: config.GovernanceConfig{
			Namespaces: []config.NamespaceConfig{{Name: "app-a", Token: "token-a"}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}

	decision, err := rt.RouteDecisionForNamespace("app-a", protocolRequest("GET", "user:42:profile"))
	if err != nil {
		t.Fatal(err)
	}
	if decision.Cluster != "redis-b" || decision.Rule != "app-profile" {
		t.Fatalf("namespace pattern decision=%+v", decision)
	}

	decision, err = rt.RouteDecisionForNamespace("app-b", protocolRequest("GET", "user:42:profile"))
	if err != nil {
		t.Fatal(err)
	}
	if decision.Cluster != "redis-a" || decision.Rule != "default" {
		t.Fatalf("namespace mismatch decision=%+v", decision)
	}

	decision, err = rt.RouteDecision(protocolRequest("GET", "{order}:1"))
	if err != nil {
		t.Fatal(err)
	}
	if decision.Cluster != "redis-c" || decision.Rule != "order-tag" {
		t.Fatalf("hash tag decision=%+v", decision)
	}
}

func TestSnapshotInfoIncludesConvergenceFields(t *testing.T) {
	cfg := managerTestConfig("127.0.0.1:6379", 1, nil)
	cfg.Instance.ProxyID = "proxy-go-1"
	cfg.Instance.Group = "frontend"
	cfg.Instance.AdvertiseIP = "10.0.0.1"
	cfg.Instance.AdvertisePort = 6379
	manager, err := NewManager(cfg)
	if err != nil {
		t.Fatal(err)
	}
	info := manager.SnapshotInfo()
	if info.ProxyID != "proxy-go-1" {
		t.Fatalf("proxyId=%q", info.ProxyID)
	}
	if info.Group != "frontend" || info.AdvertiseIP != "10.0.0.1" || info.AdvertisePort != 6379 {
		t.Fatalf("instance info=%+v", info)
	}
	if info.ConfigHash == "" {
		t.Fatal("configHash is empty")
	}
	if info.LastApplyResult != "startup" {
		t.Fatalf("lastApplyResult=%q", info.LastApplyResult)
	}
	manager.MarkPoll()
	if got := manager.SnapshotInfo().LastPollTime; got == 0 {
		t.Fatal("lastPollTime was not updated")
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
	info := manager.SnapshotInfo()
	if info.ProxyID != base.Instance.ProxyID || info.Group != base.Instance.Group || info.AdvertiseIP != base.Instance.AdvertiseIP || info.AdvertisePort != base.Instance.AdvertisePort {
		t.Fatalf("hot reload did not preserve local instance: %+v base=%+v", info, base.Instance)
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
		Instance: config.InstanceConfig{Group: "frontend", AdvertiseIP: "10.0.0.1"},
		Server:   config.ServerConfig{Listen: "127.0.0.1:6379"},
		Admin:    config.AdminConfig{Listen: "127.0.0.1:8080"},
		Mode:     "standalone",
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
