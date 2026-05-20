package backend

import (
	"bufio"
	"net"
	"testing"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
	"go.uber.org/zap"
)

func TestSelectClientByAffinityIsStable(t *testing.T) {
	first := &Client{}
	first.active.Store(true)
	second := &Client{}
	second.active.Store(true)
	pools := &Pools{
		conns: map[string][]*Client{
			"127.0.0.1:7000": {first, second},
		},
	}

	if got := pools.selectClientByAffinity("127.0.0.1:7000", 1); got != second {
		t.Fatalf("selected client=%p want %p", got, second)
	}
	if got := pools.selectClientByAffinity("127.0.0.1:7000", 1); got != second {
		t.Fatalf("selection is not stable: got %p want %p", got, second)
	}
}

func TestSelectClientByAffinityFallsBackToNextActive(t *testing.T) {
	first := &Client{}
	second := &Client{}
	second.active.Store(true)
	pools := &Pools{
		conns: map[string][]*Client{
			"127.0.0.1:7000": {first, second},
		},
	}

	if got := pools.selectClientByAffinity("127.0.0.1:7000", 0); got != second {
		t.Fatalf("selected client=%p want %p", got, second)
	}
}

func TestReconnectInactiveOnceReplacesSameSlot(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		conn, err := ln.Accept()
		if err == nil {
			accepted <- conn
		}
	}()

	old := &Client{}
	addr := ln.Addr().String()
	pools := &Pools{
		conns: map[string][]*Client{
			addr: {old},
		},
		nodeReconnectSem: map[string]chan struct{}{
			addr: make(chan struct{}, nodeReconnectLimit),
		},
		reg:              metrics.NewRegistry(),
		log:              zap.NewNop(),
		defaultInflight:  1,
		maxResponseBytes: 1024,
		done:             make(chan struct{}),
		reconnectSem:     make(chan struct{}, globalReconnectLimit),
	}
	defer pools.Close()

	pools.reconnectInactiveOnce()

	var replacement *Client
	deadline := time.After(time.Second)
	for replacement == nil || replacement == old {
		select {
		case <-deadline:
			t.Fatal("inactive client was not replaced")
		default:
		}
		pools.mu.RLock()
		replacement = pools.conns[addr][0]
		pools.mu.RUnlock()
		time.Sleep(10 * time.Millisecond)
	}
	if replacement == old {
		t.Fatal("inactive client was not replaced")
	}
	if !replacement.isActive() {
		t.Fatal("replacement is not active")
	}
	select {
	case conn := <-accepted:
		defer conn.Close()
	case <-time.After(time.Second):
		t.Fatal("backend did not receive reconnect")
	}
}

func TestReconnectInactiveOnceBacksOffAfterFailure(t *testing.T) {
	old := &Client{}
	addr := "127.0.0.1:1"
	pools := &Pools{
		conns: map[string][]*Client{
			addr: {old},
		},
		nodeReconnectSem: map[string]chan struct{}{
			addr: make(chan struct{}, nodeReconnectLimit),
		},
		reg:              metrics.NewRegistry(),
		log:              zap.NewNop(),
		defaultInflight:  1,
		maxResponseBytes: 1024,
		done:             make(chan struct{}),
		reconnectSem:     make(chan struct{}, globalReconnectLimit),
	}
	defer pools.Close()

	pools.reconnectInactiveOnce()

	deadline := time.After(time.Second)
	for old.nextReconnectAt.Load() == 0 {
		select {
		case <-deadline:
			t.Fatal("reconnect failure did not schedule backoff")
		default:
		}
		time.Sleep(10 * time.Millisecond)
	}
	if old.reconnectDelayMS.Load() <= int64(initialReconnectDelay/time.Millisecond) {
		t.Fatal("reconnect delay did not increase")
	}
}

func TestDoAsyncAskingSkipsAskingResponse(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	seen := make(chan []string, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		br := bufio.NewReader(conn)
		first, err := protocol.ReadFrameRaw(br, 1024)
		if err != nil {
			return
		}
		_, _ = conn.Write([]byte("+OK\r\n"))
		second, err := protocol.ReadFrameRaw(br, 1024)
		if err != nil {
			return
		}
		_, _ = conn.Write([]byte("$3\r\nbar\r\n"))
		seen <- []string{string(first), string(second)}
	}()

	cfg := &config.Config{
		Mode: "standalone",
		Backends: config.BackendConfig{Clusters: []config.ClusterConfig{{
			Name:  "redis-a",
			Nodes: []string{ln.Addr().String()},
			Pool:  config.PoolConfig{ConnectionsPerNode: 1, MaxInflightPerConnection: 8},
		}}},
		Limits:  config.LimitsConfig{MaxResponseBytes: 1024},
		Routing: config.RoutingConfig{DefaultCluster: "redis-a"},
	}
	pools, err := NewPools(cfg, metrics.NewRegistry(), zap.NewNop())
	if err != nil {
		t.Fatal(err)
	}
	defer pools.Close()

	done := make(chan Result, 1)
	if err := pools.DoAsyncAsking(ln.Addr().String(), 0, []byte("*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n"), func(result Result) { done <- result }); err != nil {
		t.Fatal(err)
	}

	select {
	case result := <-done:
		if result.Err != nil {
			t.Fatal(result.Err)
		}
		if string(result.Response) != "$3\r\nbar\r\n" {
			t.Fatalf("response=%q", result.Response)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for ASKING response")
	}
	select {
	case frames := <-seen:
		if frames[0] != "*1\r\n$6\r\nASKING\r\n" {
			t.Fatalf("first frame=%q", frames[0])
		}
		if frames[1] != "*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n" {
			t.Fatalf("second frame=%q", frames[1])
		}
	case <-time.After(time.Second):
		t.Fatal("backend did not receive expected frames")
	}
}
