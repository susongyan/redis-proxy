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
		reg:              metrics.NewRegistry(),
		log:              zap.NewNop(),
		defaultInflight:  1,
		maxResponseBytes: 1024,
		done:             make(chan struct{}),
	}
	defer pools.Close()

	pools.reconnectInactiveOnce()

	replacement := pools.conns[addr][0]
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
