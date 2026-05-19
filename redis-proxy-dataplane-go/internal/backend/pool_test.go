package backend

import (
	"net"
	"testing"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
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
