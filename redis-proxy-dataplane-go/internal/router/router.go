package router

import (
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"

	"github.com/example/redis-proxy-dataplane-go/internal/backend"
	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
)

const Slots = 16384

var clusterSlotsCommand = []byte("*2\r\n$7\r\nCLUSTER\r\n$5\r\nSLOTS\r\n")

type Router struct {
	mode      string
	cluster   config.ClusterConfig
	slotMu    sync.RWMutex
	slotNodes [Slots]string
}

func New(cfg *config.Config) (*Router, error) {
	for _, cluster := range cfg.Backends.Clusters {
		if cluster.Name == cfg.Routing.DefaultCluster {
			return &Router{mode: cfg.Mode, cluster: cluster}, nil
		}
	}
	return nil, fmt.Errorf("default cluster %q not found", cfg.Routing.DefaultCluster)
}

func (r *Router) Route(req protocol.Request) (string, error) {
	if len(r.cluster.Nodes) == 1 || r.mode == "standalone" {
		return r.cluster.Nodes[0], nil
	}
	key, ok := ExtractKey(req.Args)
	if !ok {
		return r.cluster.Nodes[0], nil
	}
	slot := Slot(key)
	if addr, ok := r.slotAddr(slot); ok {
		return addr, nil
	}
	return r.cluster.Nodes[slot%len(r.cluster.Nodes)], nil
}

func (r *Router) RefreshSlots(pools *backend.Pools) error {
	if r.mode != "cluster" || len(r.cluster.Nodes) == 0 {
		return nil
	}
	var lastErr error
	for _, seed := range r.cluster.Nodes {
		resp, err := pools.Do(seed, clusterSlotsCommand, 0)
		if err != nil {
			lastErr = err
			continue
		}
		slots, err := r.parseClusterSlots(resp)
		if err != nil {
			lastErr = err
			continue
		}
		for _, addr := range slots {
			if addr == "" {
				continue
			}
			_ = pools.Ensure(addr)
		}
		return nil
	}
	return lastErr
}

func (r *Router) UpdateMoved(response []byte, pools *backend.Pools) {
	text := string(response)
	if !strings.HasPrefix(text, "-MOVED ") {
		return
	}
	fields := strings.Fields(text)
	if len(fields) < 3 {
		return
	}
	slot, err := strconv.Atoi(fields[1])
	if err != nil || slot < 0 || slot >= Slots {
		return
	}
	addr := r.normalizeAddr(fields[2])
	r.setSlot(slot, addr)
	if pools != nil {
		_ = pools.Ensure(addr)
	}
}

func (r *Router) SlotCoverage() int {
	r.slotMu.RLock()
	defer r.slotMu.RUnlock()
	covered := 0
	for _, addr := range r.slotNodes {
		if addr != "" {
			covered++
		}
	}
	return covered
}

func (r *Router) parseClusterSlots(raw []byte) (map[int]string, error) {
	value, err := protocol.ParseValue(raw)
	if err != nil {
		return nil, err
	}
	if value.Kind != protocol.Array {
		return nil, fmt.Errorf("CLUSTER SLOTS returned %q", value.Kind)
	}
	next := [Slots]string{}
	seen := map[int]string{}
	for _, slotRange := range value.Array {
		if slotRange.Kind != protocol.Array || len(slotRange.Array) < 3 {
			continue
		}
		start := int(slotRange.Array[0].Int)
		end := int(slotRange.Array[1].Int)
		master := slotRange.Array[2]
		if master.Kind != protocol.Array || len(master.Array) < 2 {
			continue
		}
		host := string(master.Array[0].Bytes)
		port := int(master.Array[1].Int)
		addr := r.normalizeAddr(net.JoinHostPort(host, strconv.Itoa(port)))
		if start < 0 {
			start = 0
		}
		if end >= Slots {
			end = Slots - 1
		}
		for slot := start; slot <= end; slot++ {
			next[slot] = addr
			seen[slot] = addr
		}
	}
	r.slotMu.Lock()
	r.slotNodes = next
	r.slotMu.Unlock()
	return seen, nil
}

func (r *Router) normalizeAddr(addr string) string {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		parts := strings.Split(addr, ":")
		if len(parts) >= 2 {
			host = strings.Join(parts[:len(parts)-1], ":")
			port = parts[len(parts)-1]
		} else {
			return addr
		}
	}
	for _, node := range r.cluster.Nodes {
		_, nodePort, err := net.SplitHostPort(node)
		if err != nil {
			continue
		}
		if nodePort == port {
			return node
		}
	}
	return net.JoinHostPort(host, port)
}

func (r *Router) slotAddr(slot int) (string, bool) {
	if slot < 0 || slot >= Slots {
		return "", false
	}
	r.slotMu.RLock()
	addr := r.slotNodes[slot]
	r.slotMu.RUnlock()
	return addr, addr != ""
}

func (r *Router) setSlot(slot int, addr string) {
	if slot < 0 || slot >= Slots {
		return
	}
	r.slotMu.Lock()
	r.slotNodes[slot] = addr
	r.slotMu.Unlock()
}

func ExtractKey(args [][]byte) ([]byte, bool) {
	if len(args) < 2 {
		return nil, false
	}
	return hashTag(args[1]), true
}

func hashTag(key []byte) []byte {
	start := -1
	for i, b := range key {
		if b == '{' {
			start = i
			break
		}
	}
	if start < 0 {
		return key
	}
	for i := start + 1; i < len(key); i++ {
		if key[i] == '}' {
			if i == start+1 {
				return key
			}
			return key[start+1 : i]
		}
	}
	return key
}

func Slot(key []byte) int {
	return int(crc16(hashTag(key)) % Slots)
}

func crc16(data []byte) uint16 {
	var crc uint16
	for _, b := range data {
		crc ^= uint16(b) << 8
		for i := 0; i < 8; i++ {
			if crc&0x8000 != 0 {
				crc = (crc << 1) ^ 0x1021
			} else {
				crc <<= 1
			}
		}
	}
	return crc
}
