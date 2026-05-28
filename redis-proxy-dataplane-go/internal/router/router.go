package router

import (
	"bytes"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/backend"
	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/governance"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
)

const Slots = 16384

var clusterSlotsCommand = []byte("*2\r\n$7\r\nCLUSTER\r\n$5\r\nSLOTS\r\n")

type Router struct {
	proxyID         string
	configHash      string
	lastApplyResult atomic.Value
	lastApplyTime   atomic.Int64
	lastPollTime    atomic.Int64
	mode            string
	epoch           int64
	defaultCluster  string
	clusters        map[string]config.ClusterConfig
	rules           []config.RouteRuleConfig
	limits          config.LimitsConfig
	analysis        config.AnalysisConfig
	governance      config.GovernanceConfig
	states          map[string]*clusterState
}

type Decision struct {
	Addr    string
	Cluster string
	Rule    string
	Epoch   int64
}

type SnapshotInfo struct {
	ProxyID         string                   `json:"proxyId"`
	Epoch           int64                    `json:"epoch"`
	ConfigHash      string                   `json:"configHash"`
	LastApplyResult string                   `json:"lastApplyResult"`
	LastApplyTime   int64                    `json:"lastApplyTime"`
	LastPollTime    int64                    `json:"lastPollTime"`
	Mode            string                   `json:"mode"`
	DefaultCluster  string                   `json:"defaultCluster"`
	RouteClusters   []string                 `json:"routeClusters"`
	Rules           []config.RouteRuleConfig `json:"rules"`
	Governance      map[string]any           `json:"governance"`
}

type Manager struct {
	current atomic.Value
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
	rt := &Router{
		proxyID:        cfg.Instance.ProxyID,
		configHash:     config.SnapshotHash(cfg),
		mode:           cfg.Mode,
		epoch:          cfg.Routing.RouteEpoch,
		defaultCluster: cfg.Routing.DefaultCluster,
		clusters:       clusters,
		rules:          append([]config.RouteRuleConfig(nil), cfg.Routing.Rules...),
		limits:         cfg.Limits,
		analysis:       cfg.Analysis,
		governance:     cfg.Governance,
		states:         states,
	}
	rt.lastApplyResult.Store("startup")
	rt.lastApplyTime.Store(time.Now().Unix())
	return rt, nil
}

func NewManager(cfg *config.Config) (*Manager, error) {
	rt, err := New(cfg)
	if err != nil {
		return nil, err
	}
	m := &Manager{}
	m.current.Store(rt)
	return m, nil
}

func (m *Manager) Current() *Router {
	return m.current.Load().(*Router)
}

func (m *Manager) Route(req protocol.Request) (string, error) {
	return m.Current().Route(req)
}

func (m *Manager) RouteDecision(req protocol.Request) (Decision, error) {
	return m.Current().RouteDecision(req)
}

func (m *Manager) RouteDecisionForNamespace(namespace string, req protocol.Request) (Decision, error) {
	return m.Current().RouteDecisionForNamespace(namespace, req)
}

func (m *Manager) UpdateMoved(response []byte, pools *backend.Pools) {
	m.Current().UpdateMoved(response, pools)
}

func (m *Manager) AskTarget(response []byte, clusterName string, pools *backend.Pools) (string, error) {
	return m.Current().AskTarget(response, clusterName, pools)
}

func (m *Manager) RefreshSlots(pools *backend.Pools) error {
	return m.Current().RefreshSlots(pools)
}

func (m *Manager) SnapshotInfo() SnapshotInfo {
	return m.Current().SnapshotInfo()
}

func (m *Manager) MarkPoll() {
	m.Current().lastPollTime.Store(time.Now().Unix())
}

func (m *Manager) RecordApplyResult(result string) {
	if result == "" {
		result = "error"
	}
	current := m.Current()
	current.lastApplyResult.Store(result)
	current.lastApplyTime.Store(time.Now().Unix())
}

func (m *Manager) Governance() config.GovernanceConfig {
	return m.Current().governance
}

func (m *Manager) Limits() config.LimitsConfig {
	return m.Current().limits
}

func (m *Manager) Analysis() config.AnalysisConfig {
	return m.Current().analysis
}

func (m *Manager) CurrentEpoch() int64 {
	return m.Current().epoch
}

func (m *Manager) DefaultNodes() []string {
	return m.Current().DefaultNodes()
}

func (m *Manager) RouteClusters() []string {
	return m.Current().RouteClusters()
}

func (m *Manager) ClusterSlotCoverage(clusterName string) int {
	return m.Current().ClusterSlotCoverage(clusterName)
}

func (m *Manager) SlotCoverage() int {
	return m.Current().SlotCoverage()
}

func (m *Manager) ClusterSlotOwners(clusterName string) []string {
	return m.Current().ClusterSlotOwners(clusterName)
}

func (m *Manager) ApplyConfig(cfg *config.Config, pools *backend.Pools) (string, error) {
	config.ApplyDefaults(cfg)
	cfg.Instance.ProxyID = m.Current().proxyID
	if err := cfg.Validate(); err != nil {
		m.RecordApplyResult("invalid")
		return "invalid", err
	}
	old := m.Current()
	if cfg.Mode != old.mode {
		m.RecordApplyResult("runtime_shape")
		return "runtime_shape", fmt.Errorf("mode changes are not hot reloadable")
	}
	if cfg.Routing.RouteEpoch <= old.epoch {
		m.RecordApplyResult("stale_epoch")
		return "stale_epoch", fmt.Errorf("routeEpoch %d must be greater than current %d", cfg.Routing.RouteEpoch, old.epoch)
	}
	for _, cluster := range cfg.Backends.Clusters {
		for _, node := range cluster.Nodes {
			if err := pools.Ensure(node); err != nil {
				m.RecordApplyResult("backend_ensure")
				return "backend_ensure", err
			}
		}
	}
	next, err := New(cfg)
	if err != nil {
		m.RecordApplyResult("invalid")
		return "invalid", err
	}
	next.inheritSlots(old)
	next.lastPollTime.Store(old.lastPollTime.Load())
	next.lastApplyResult.Store("success")
	next.lastApplyTime.Store(time.Now().Unix())
	if cfg.Mode == "cluster" {
		if err := next.RefreshSlots(pools); err != nil {
			m.RecordApplyResult("slot_refresh")
			return "slot_refresh", err
		}
	}
	m.current.Store(next)
	return "success", nil
}

func (r *Router) Route(req protocol.Request) (string, error) {
	decision, err := r.RouteDecision(req)
	if err != nil {
		return "", err
	}
	return decision.Addr, nil
}

func (r *Router) RouteDecision(req protocol.Request) (Decision, error) {
	return r.RouteDecisionForNamespace("", req)
}

func (r *Router) RouteDecisionForNamespace(namespace string, req protocol.Request) (Decision, error) {
	clusterName, ruleName := r.selectCluster(namespace, req)
	cluster, ok := r.clusters[clusterName]
	if !ok {
		return Decision{}, fmt.Errorf("route cluster %q not found", clusterName)
	}
	if len(cluster.Nodes) == 0 {
		return Decision{}, fmt.Errorf("route cluster %q has no backend nodes", clusterName)
	}
	if len(cluster.Nodes) == 1 || r.mode == "standalone" {
		return Decision{Addr: cluster.Nodes[0], Cluster: clusterName, Rule: ruleName, Epoch: r.epoch}, nil
	}
	key, ok := ExtractKey(req.Args)
	if !ok {
		return Decision{Addr: cluster.Nodes[0], Cluster: clusterName, Rule: ruleName, Epoch: r.epoch}, nil
	}
	slot := Slot(key)
	if addr, ok := r.slotAddr(clusterName, slot); ok {
		return Decision{Addr: addr, Cluster: clusterName, Rule: ruleName, Epoch: r.epoch}, nil
	}
	return Decision{Addr: cluster.Nodes[slot%len(cluster.Nodes)], Cluster: clusterName, Rule: ruleName, Epoch: r.epoch}, nil
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
	target, ok := parseRedirection(response, "MOVED")
	if !ok {
		return
	}
	clusterName, addr := r.normalizeMovedAddr(target.Addr)
	r.setSlot(clusterName, target.Slot, addr)
	if pools != nil {
		_ = pools.Ensure(addr)
	}
}

func (r *Router) AskTarget(response []byte, clusterName string, pools *backend.Pools) (string, error) {
	target, ok := parseRedirection(response, "ASK")
	if !ok {
		return "", fmt.Errorf("invalid ASK response")
	}
	if target.Slot < 0 || target.Slot >= Slots {
		return "", fmt.Errorf("invalid ASK slot %d", target.Slot)
	}
	addr := r.normalizeAddr(clusterName, target.Addr)
	if pools != nil {
		if err := pools.Ensure(addr); err != nil {
			return "", err
		}
	}
	return addr, nil
}

type redirectionTarget struct {
	Slot int
	Addr string
}

func parseRedirection(response []byte, kind string) (redirectionTarget, bool) {
	text := string(response)
	prefix := "-" + kind + " "
	if !strings.HasPrefix(text, prefix) {
		return redirectionTarget{}, false
	}
	fields := strings.Fields(text)
	if len(fields) < 3 {
		return redirectionTarget{}, false
	}
	slot, err := strconv.Atoi(fields[1])
	if err != nil || slot < 0 || slot >= Slots {
		return redirectionTarget{}, false
	}
	return redirectionTarget{Slot: slot, Addr: fields[2]}, true
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

func (r *Router) SnapshotInfo() SnapshotInfo {
	lastApplyResult, _ := r.lastApplyResult.Load().(string)
	return SnapshotInfo{
		ProxyID:         r.proxyID,
		Epoch:           r.epoch,
		ConfigHash:      r.configHash,
		LastApplyResult: lastApplyResult,
		LastApplyTime:   r.lastApplyTime.Load(),
		LastPollTime:    r.lastPollTime.Load(),
		Mode:            r.mode,
		DefaultCluster:  r.defaultCluster,
		RouteClusters:   r.RouteClusters(),
		Rules:           append([]config.RouteRuleConfig(nil), r.rules...),
		Governance:      governance.Summary(r.governance),
	}
}

func (r *Router) ClusterNodes(clusterName string) []string {
	cluster, ok := r.clusters[clusterName]
	if !ok {
		return nil
	}
	return append([]string(nil), cluster.Nodes...)
}

func (r *Router) selectCluster(namespace string, req protocol.Request) (string, string) {
	rawKey, ok := RawKey(req.Args)
	var tag []byte
	if ok {
		tag = hashTag(rawKey)
	}
	for _, rule := range r.rules {
		if rule.TrafficPercent <= 0 {
			continue
		}
		if rule.Namespace != "" && rule.Namespace != namespace {
			continue
		}
		if rule.KeyPrefix != "" && (!ok || !bytes.HasPrefix(rawKey, []byte(rule.KeyPrefix))) {
			continue
		}
		if rule.KeyPattern != "" && (!ok || !matchGlob([]byte(rule.KeyPattern), rawKey)) {
			continue
		}
		if rule.HashTag != "" && (!ok || string(tag) != rule.HashTag) {
			continue
		}
		sampleKey := rawKey
		if len(sampleKey) == 0 {
			sampleKey = []byte(namespace)
		}
		if rule.TrafficPercent >= 100 || int(crc16(sampleKey)%100) < rule.TrafficPercent {
			return rule.Cluster, rule.Name
		}
	}
	return r.defaultCluster, "default"
}

func matchGlob(pattern []byte, value []byte) bool {
	p, v := 0, 0
	star, match := -1, 0
	for v < len(value) {
		if p < len(pattern) && (pattern[p] == '?' || pattern[p] == value[v]) {
			p++
			v++
			continue
		}
		if p < len(pattern) && pattern[p] == '*' {
			star = p
			match = v
			p++
			continue
		}
		if star >= 0 {
			p = star + 1
			match++
			v = match
			continue
		}
		return false
	}
	for p < len(pattern) && pattern[p] == '*' {
		p++
	}
	return p == len(pattern)
}

func (r *Router) inheritSlots(old *Router) {
	for clusterName, state := range r.states {
		oldState := old.states[clusterName]
		if oldState == nil {
			continue
		}
		oldState.mu.RLock()
		state.mu.Lock()
		state.slotNodes = oldState.slotNodes
		state.mu.Unlock()
		oldState.mu.RUnlock()
	}
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
