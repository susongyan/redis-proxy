package router

import (
	"bytes"
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
	mode           string
	defaultCluster string
	clusters       map[string]config.ClusterConfig
	rules          []config.RouteRuleConfig
	states         map[string]*clusterState
}

type clusterState struct {
	mu        sync.RWMutex
	slotNodes [Slots]string
}

func New(cfg *config.Config) (*Router, error) {
	clusters := make(map[string]config.ClusterConfig, len(cfg.Backends.Clusters))
	states := make(map[string]*clusterState, len(cfg.Backends.Clusters))
	for _, cluster := range cfg.Backends.Clusters {
		clusters[cluster.Name] = cluster
		states[cluster.Name] = &clusterState{}
	}
	if _, ok := clusters[cfg.Routing.DefaultCluster]; !ok {
		return nil, fmt.Errorf("default cluster %q not found", cfg.Routing.DefaultCluster)
	}
	return &Router{
		mode:           cfg.Mode,
		defaultCluster: cfg.Routing.DefaultCluster,
		clusters:       clusters,
		rules:          append([]config.RouteRuleConfig(nil), cfg.Routing.Rules...),
		states:         states,
	}, nil
}

func (r *Router) Route(req protocol.Request) (string, error) {
	clusterName := r.selectCluster(req)
	cluster, ok := r.clusters[clusterName]
	if !ok {
		return "", fmt.Errorf("route cluster %q not found", clusterName)
	}
	if len(cluster.Nodes) == 0 {
		return "", fmt.Errorf("route cluster %q has no backend nodes", clusterName)
	}
	if len(cluster.Nodes) == 1 || r.mode == "standalone" {
		return cluster.Nodes[0], nil
	}
	key, ok := ExtractKey(req.Args)
	if !ok {
		return cluster.Nodes[0], nil
	}
	slot := Slot(key)
	if addr, ok := r.slotAddr(clusterName, slot); ok {
		return addr, nil
	}
	return cluster.Nodes[slot%len(cluster.Nodes)], nil
}

func (r *Router) RefreshSlots(pools *backend.Pools) error {
	if r.mode != "cluster" {
		return nil
	}
	var refreshErr error
	for clusterName, cluster := range r.clusters {
		if len(cluster.Nodes) == 0 {
			continue
		}
		var lastErr error
		refreshed := false
		for _, seed := range cluster.Nodes {
			resp, err := pools.Do(seed, clusterSlotsCommand, 0)
			if err != nil {
				lastErr = err
				continue
			}
			slots, err := r.parseClusterSlotsFor(clusterName, resp)
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
			refreshed = true
			break
		}
		if !refreshed {
			if lastErr == nil {
				lastErr = fmt.Errorf("cluster %q slot refresh failed", clusterName)
			}
			refreshErr = lastErr
		}
	}
	return refreshErr
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
	clusterName, addr := r.normalizeMovedAddr(fields[2])
	r.setSlot(clusterName, slot, addr)
	if pools != nil {
		_ = pools.Ensure(addr)
	}
}

func (r *Router) SlotCoverage() int {
	return r.ClusterSlotCoverage(r.defaultCluster)
}

func (r *Router) ClusterSlotCoverage(clusterName string) int {
	state := r.states[clusterName]
	if state == nil {
		return 0
	}
	state.mu.RLock()
	defer state.mu.RUnlock()
	covered := 0
	for _, addr := range state.slotNodes {
		if addr != "" {
			covered++
		}
	}
	return covered
}

func (r *Router) SlotOwners() []string {
	owners := []string{}
	seen := map[string]bool{}
	for _, clusterName := range r.RouteClusters() {
		for _, owner := range r.ClusterSlotOwners(clusterName) {
			if seen[owner] {
				continue
			}
			seen[owner] = true
			owners = append(owners, owner)
		}
	}
	return owners
}

func (r *Router) ClusterSlotOwners(clusterName string) []string {
	state := r.states[clusterName]
	if state == nil {
		return nil
	}
	state.mu.RLock()
	defer state.mu.RUnlock()
	seen := map[string]bool{}
	owners := make([]string, 0, len(r.clusters[clusterName].Nodes))
	for _, addr := range state.slotNodes {
		if addr == "" || seen[addr] {
			continue
		}
		seen[addr] = true
		owners = append(owners, addr)
	}
	return owners
}

func (r *Router) DefaultNodes() []string {
	return r.ClusterNodes(r.defaultCluster)
}

func (r *Router) parseClusterSlots(raw []byte) (map[int]string, error) {
	return r.parseClusterSlotsFor(r.defaultCluster, raw)
}

func (r *Router) parseClusterSlotsFor(clusterName string, raw []byte) (map[int]string, error) {
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
		addr := r.normalizeAddr(clusterName, net.JoinHostPort(host, strconv.Itoa(port)))
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
	state := r.states[clusterName]
	if state == nil {
		return nil, fmt.Errorf("cluster %q not found", clusterName)
	}
	state.mu.Lock()
	state.slotNodes = next
	state.mu.Unlock()
	return seen, nil
}

func (r *Router) normalizeAddr(clusterName string, addr string) string {
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
	for _, node := range r.clusters[clusterName].Nodes {
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

func (r *Router) normalizeMovedAddr(addr string) (string, string) {
	for clusterName := range r.clusters {
		normalized := r.normalizeAddr(clusterName, addr)
		for _, node := range r.clusters[clusterName].Nodes {
			if normalized == node {
				return clusterName, normalized
			}
		}
	}
	return r.defaultCluster, r.normalizeAddr(r.defaultCluster, addr)
}

func (r *Router) slotAddr(clusterName string, slot int) (string, bool) {
	if slot < 0 || slot >= Slots {
		return "", false
	}
	state := r.states[clusterName]
	if state == nil {
		return "", false
	}
	state.mu.RLock()
	addr := state.slotNodes[slot]
	state.mu.RUnlock()
	return addr, addr != ""
}

func (r *Router) setSlot(clusterName string, slot int, addr string) {
	if slot < 0 || slot >= Slots {
		return
	}
	state := r.states[clusterName]
	if state == nil {
		return
	}
	state.mu.Lock()
	state.slotNodes[slot] = addr
	state.mu.Unlock()
}

func (r *Router) RouteClusters() []string {
	seen := map[string]bool{r.defaultCluster: true}
	clusters := []string{r.defaultCluster}
	for _, rule := range r.rules {
		if seen[rule.Cluster] {
			continue
		}
		seen[rule.Cluster] = true
		clusters = append(clusters, rule.Cluster)
	}
	return clusters
}

func (r *Router) ClusterNodes(clusterName string) []string {
	cluster, ok := r.clusters[clusterName]
	if !ok {
		return nil
	}
	return append([]string(nil), cluster.Nodes...)
}

func (r *Router) selectCluster(req protocol.Request) string {
	rawKey, ok := RawKey(req.Args)
	if !ok {
		return r.defaultCluster
	}
	tag := hashTag(rawKey)
	for _, rule := range r.rules {
		if rule.TrafficPercent <= 0 {
			continue
		}
		if rule.KeyPrefix != "" && !bytes.HasPrefix(rawKey, []byte(rule.KeyPrefix)) {
			continue
		}
		if rule.HashTag != "" && string(tag) != rule.HashTag {
			continue
		}
		if rule.TrafficPercent >= 100 || int(crc16(rawKey)%100) < rule.TrafficPercent {
			return rule.Cluster
		}
	}
	return r.defaultCluster
}

func ExtractKey(args [][]byte) ([]byte, bool) {
	key, ok := RawKey(args)
	if !ok {
		return nil, false
	}
	return hashTag(key), true
}

func RawKey(args [][]byte) ([]byte, bool) {
	if len(args) < 2 {
		return nil, false
	}
	return args[1], true
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
