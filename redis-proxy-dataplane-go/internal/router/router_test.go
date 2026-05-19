package router

import (
	"testing"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
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
		mode: "cluster",
		cluster: config.ClusterConfig{
			Nodes: []string{"127.0.0.1:7000", "127.0.0.1:7001"},
		},
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
		mode: "cluster",
		cluster: config.ClusterConfig{
			Nodes: []string{"127.0.0.1:7000"},
		},
	}
	rt.UpdateMoved([]byte("-MOVED 42 172.18.0.2:7000\r\n"), nil)
	addr, ok := rt.slotAddr(42)
	if !ok || addr != "127.0.0.1:7000" {
		t.Fatalf("slot 42 addr=%q ok=%v", addr, ok)
	}
	if got := rt.SlotCoverage(); got != 1 {
		t.Fatalf("slot coverage=%d want 1", got)
	}
}

func TestNormalizeAddrMapsClusterContainerHostnameByPort(t *testing.T) {
	rt := &Router{
		mode: "cluster",
		cluster: config.ClusterConfig{
			Nodes: []string{"127.0.0.1:7100", "127.0.0.1:7101"},
		},
	}
	if got := rt.normalizeAddr("redis-proxy-cluster-7101:7101"); got != "127.0.0.1:7101" {
		t.Fatalf("normalized addr=%q", got)
	}
}

func protocolRequest(args ...string) protocol.Request {
	values := make([][]byte, 0, len(args))
	for _, arg := range args {
		values = append(values, []byte(arg))
	}
	return protocol.Request{Args: values}
}
