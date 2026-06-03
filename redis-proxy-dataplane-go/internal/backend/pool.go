package backend

import (
	"bufio"
	"errors"
	"fmt"
	"math/rand"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
	"go.uber.org/zap"
)

var ErrBackendUnavailable = errors.New("backend unavailable")

const (
	initialReconnectDelay = 500 * time.Millisecond
	maxReconnectDelay     = 30 * time.Second
	reconnectScanInterval = 500 * time.Millisecond
	globalReconnectLimit  = 64
	nodeReconnectLimit    = 2
)

type Result struct {
	Response []byte
	Err      error
}

type Callback func(Result)

type request struct {
	payload       []byte
	cb            Callback
	start         time.Time
	skipResponses int
}

type Client struct {
	addr             string
	auth             config.AuthConfig
	conn             net.Conn
	br               *bufio.Reader
	requests         chan request
	pending          chan request
	maxResponseBytes int
	reg              *metrics.Registry
	log              *zap.Logger
	done             chan struct{}
	closeOnce        sync.Once
	active           atomic.Bool
	inflight         atomic.Int64
	reconnecting     atomic.Bool
	nextReconnectAt  atomic.Int64
	reconnectDelayMS atomic.Int64
}

type Pools struct {
	mu               sync.RWMutex
	conns            map[string][]*Client
	authByNode       map[string]config.AuthConfig
	nodeReconnectSem map[string]chan struct{}
	reg              *metrics.Registry
	log              *zap.Logger
	defaultSize      int
	defaultInflight  int
	maxResponseBytes int
	done             chan struct{}
	closeOnce        sync.Once
	reconnectSem     chan struct{}
}

func NewPools(cfg *config.Config, reg *metrics.Registry, log *zap.Logger) (*Pools, error) {
	p := &Pools{
		conns:            map[string][]*Client{},
		authByNode:       map[string]config.AuthConfig{},
		nodeReconnectSem: map[string]chan struct{}{},
		reg:              reg,
		log:              log,
		maxResponseBytes: max(1, cfg.Limits.MaxResponseBytes),
		done:             make(chan struct{}),
		reconnectSem:     make(chan struct{}, globalReconnectLimit),
	}
	for _, cluster := range cfg.Backends.Clusters {
		size := cluster.Pool.ConnectionsPerNode
		if size <= 0 {
			size = 8
		}
		maxInflight := cluster.Pool.MaxInflightPerConnection
		if maxInflight <= 0 {
			maxInflight = 1024
		}
		if p.defaultSize == 0 {
			p.defaultSize = size
			p.defaultInflight = maxInflight
		}
		for _, node := range cluster.Nodes {
			p.authByNode[node] = cluster.Auth
			if err := p.Ensure(node); err != nil {
				p.Close()
				return nil, err
			}
		}
	}
	go p.reconnectLoop()
	return p, nil
}

func (p *Pools) Ensure(addr string) error {
	p.mu.RLock()
	auth := p.authByNode[addr]
	p.mu.RUnlock()
	return p.EnsureWithAuth(addr, auth)
}

func (p *Pools) EnsureWithAuth(addr string, auth config.AuthConfig) error {
	p.mu.RLock()
	_, ok := p.conns[addr]
	p.mu.RUnlock()
	if ok {
		return nil
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if _, ok := p.conns[addr]; ok {
		return nil
	}
	p.authByNode[addr] = auth
	size := p.defaultSize
	if size <= 0 {
		size = 8
	}
	maxInflight := p.defaultInflight
	if maxInflight <= 0 {
		maxInflight = 1024
	}
	clients := make([]*Client, 0, size)
	for i := 0; i < size; i++ {
		client, err := newClient(addr, auth, maxInflight, p.maxResponseBytes, p.reg, p.log)
		if err != nil {
			for _, c := range clients {
				c.close()
			}
			return err
		}
		clients = append(clients, client)
	}
	p.conns[addr] = clients
	p.nodeReconnectSem[addr] = make(chan struct{}, nodeReconnectLimit)
	p.reg.BackendDesired.WithLabelValues(addr).Set(float64(size))
	return nil
}

func (p *Pools) EnsureCluster(cluster config.ClusterConfig) error {
	for _, node := range cluster.Nodes {
		if err := p.EnsureWithAuth(node, cluster.Auth); err != nil {
			return err
		}
	}
	return nil
}

func (p *Pools) Do(addr string, payload []byte, maxResponseBytes int) ([]byte, error) {
	done := make(chan Result, 1)
	if err := p.DoAsync(addr, payload, func(result Result) { done <- result }); err != nil {
		return nil, err
	}
	result := <-done
	return result.Response, result.Err
}

func (p *Pools) DoAsync(addr string, payload []byte, cb Callback) error {
	client := p.selectClient(addr)
	if client == nil {
		p.reg.BackendUnavailable.WithLabelValues(addr, "no_active_connection").Inc()
		return ErrBackendUnavailable
	}
	return client.send(payload, 0, cb)
}

func (p *Pools) DoAsyncAffinity(addr string, affinity uint64, payload []byte, cb Callback) error {
	return p.doAsyncAffinity(addr, affinity, payload, 0, cb)
}

func (p *Pools) DoAsyncAsking(addr string, affinity uint64, payload []byte, cb Callback) error {
	asking := []byte("*1\r\n$6\r\nASKING\r\n")
	combined := make([]byte, 0, len(asking)+len(payload))
	combined = append(combined, asking...)
	combined = append(combined, payload...)
	return p.doAsyncAffinity(addr, affinity, combined, 1, cb)
}

func (p *Pools) doAsyncAffinity(addr string, affinity uint64, payload []byte, skipResponses int, cb Callback) error {
	client := p.selectClientByAffinity(addr, affinity)
	if client == nil {
		p.reg.BackendUnavailable.WithLabelValues(addr, "no_active_connection").Inc()
		return ErrBackendUnavailable
	}
	return client.send(payload, skipResponses, cb)
}

func (p *Pools) selectClient(addr string) *Client {
	p.mu.RLock()
	clients := p.conns[addr]
	p.mu.RUnlock()
	var selected *Client
	for _, client := range clients {
		if !client.isActive() {
			continue
		}
		if selected == nil || client.inflight.Load() < selected.inflight.Load() {
			selected = client
		}
	}
	return selected
}

func (p *Pools) selectClientByAffinity(addr string, affinity uint64) *Client {
	p.mu.RLock()
	clients := p.conns[addr]
	p.mu.RUnlock()
	if len(clients) == 0 {
		return nil
	}
	start := int(affinity % uint64(len(clients)))
	for i := 0; i < len(clients); i++ {
		client := clients[(start+i)%len(clients)]
		if client.isActive() {
			return client
		}
	}
	return nil
}

func (p *Pools) ActiveCount(addr string) int {
	p.mu.RLock()
	clients := p.conns[addr]
	p.mu.RUnlock()
	active := 0
	for _, client := range clients {
		if client.isActive() {
			active++
		}
	}
	return active
}

func (p *Pools) DesiredCount(addr string) int {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return len(p.conns[addr])
}

func (p *Pools) HasActive(addr string) bool {
	return p.ActiveCount(addr) > 0
}

func (p *Pools) Close() {
	p.closeOnce.Do(func() {
		close(p.done)
		p.mu.RLock()
		defer p.mu.RUnlock()
		for _, clients := range p.conns {
			for _, client := range clients {
				client.close()
			}
		}
	})
}

type reconnectCandidate struct {
	addr  string
	index int
	old   *Client
}

func (p *Pools) reconnectLoop() {
	ticker := time.NewTicker(reconnectScanInterval)
	defer ticker.Stop()
	for {
		select {
		case <-p.done:
			return
		case <-ticker.C:
			p.reconnectInactiveOnce()
		}
	}
}

func (p *Pools) reconnectInactiveOnce() {
	candidates := p.inactiveClients()
	for _, candidate := range candidates {
		select {
		case <-p.done:
			return
		default:
		}
		if !candidate.old.shouldReconnectNow(time.Now()) {
			continue
		}
		if !candidate.old.reconnecting.CompareAndSwap(false, true) {
			continue
		}
		if !p.acquireReconnect(candidate.addr) {
			candidate.old.reconnecting.Store(false)
			continue
		}
		p.reg.BackendReconnecting.WithLabelValues(candidate.addr).Inc()
		go p.reconnectCandidate(candidate)
	}
}

func (p *Pools) reconnectCandidate(candidate reconnectCandidate) {
	defer func() {
		p.releaseReconnect(candidate.addr)
		p.reg.BackendReconnecting.WithLabelValues(candidate.addr).Dec()
		candidate.old.reconnecting.Store(false)
	}()
	p.mu.RLock()
	auth := p.authByNode[candidate.addr]
	p.mu.RUnlock()
	replacement, err := newClient(candidate.addr, auth, p.defaultInflight, p.maxResponseBytes, p.reg, p.log)
	if err != nil {
		p.reg.BackendReconnects.WithLabelValues(candidate.addr, "error").Inc()
		candidate.old.scheduleNextReconnect()
		p.log.Warn("reconnect backend", zap.String("backend", candidate.addr), zap.Error(err))
		return
	}
	if p.replaceInactive(candidate, replacement) {
		p.reg.BackendReconnects.WithLabelValues(candidate.addr, "success").Inc()
		return
	}
	replacement.close()
}

func (p *Pools) inactiveClients() []reconnectCandidate {
	p.mu.RLock()
	defer p.mu.RUnlock()
	var candidates []reconnectCandidate
	for addr, clients := range p.conns {
		for index, client := range clients {
			if !client.isActive() {
				candidates = append(candidates, reconnectCandidate{addr: addr, index: index, old: client})
			}
		}
	}
	return candidates
}

func (p *Pools) replaceInactive(candidate reconnectCandidate, replacement *Client) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	clients := p.conns[candidate.addr]
	if candidate.index >= len(clients) || clients[candidate.index] != candidate.old || candidate.old.isActive() {
		return false
	}
	clients[candidate.index] = replacement
	return true
}

func (p *Pools) acquireReconnect(addr string) bool {
	select {
	case p.reconnectSem <- struct{}{}:
	default:
		return false
	}
	p.mu.RLock()
	nodeSem := p.nodeReconnectSem[addr]
	p.mu.RUnlock()
	if nodeSem == nil {
		<-p.reconnectSem
		return false
	}
	select {
	case nodeSem <- struct{}{}:
		return true
	default:
		<-p.reconnectSem
		return false
	}
}

func (p *Pools) releaseReconnect(addr string) {
	p.mu.RLock()
	nodeSem := p.nodeReconnectSem[addr]
	p.mu.RUnlock()
	if nodeSem != nil {
		select {
		case <-nodeSem:
		default:
		}
	}
	select {
	case <-p.reconnectSem:
	default:
	}
}

func newClient(addr string, auth config.AuthConfig, maxInflight int, maxResponseBytes int, reg *metrics.Registry, log *zap.Logger) (*Client, error) {
	conn, err := net.DialTimeout("tcp", addr, 2*time.Second)
	if err != nil {
		return nil, err
	}
	if err := authenticate(conn, auth, maxResponseBytes); err != nil {
		_ = conn.Close()
		return nil, err
	}
	client := &Client{
		addr:             addr,
		auth:             auth,
		conn:             conn,
		br:               bufio.NewReader(conn),
		requests:         make(chan request, maxInflight),
		pending:          make(chan request, maxInflight),
		maxResponseBytes: maxResponseBytes,
		reg:              reg,
		log:              log,
		done:             make(chan struct{}),
	}
	client.active.Store(true)
	client.reconnectDelayMS.Store(int64(initialReconnectDelay / time.Millisecond))
	reg.BackendConns.Inc()
	reg.BackendConnsByNode.WithLabelValues(addr).Inc()
	go client.writeLoop()
	go client.readLoop()
	return client, nil
}

func authenticate(conn net.Conn, auth config.AuthConfig, maxResponseBytes int) error {
	if !auth.Enabled {
		return nil
	}
	if auth.Password == "" {
		return errors.New("backend auth password is required")
	}
	payload := authPayload(auth)
	if _, err := conn.Write(payload); err != nil {
		return err
	}
	resp, err := protocol.ReadFrameRaw(bufio.NewReader(conn), max(1, maxResponseBytes))
	if err != nil {
		return err
	}
	if len(resp) < 3 || resp[0] != '+' {
		return fmt.Errorf("backend auth failed: %q", string(resp))
	}
	return nil
}

func authPayload(auth config.AuthConfig) []byte {
	if auth.Username == "" {
		return []byte(fmt.Sprintf("*2\r\n$4\r\nAUTH\r\n$%d\r\n%s\r\n", len(auth.Password), auth.Password))
	}
	return []byte(fmt.Sprintf("*3\r\n$4\r\nAUTH\r\n$%d\r\n%s\r\n$%d\r\n%s\r\n", len(auth.Username), auth.Username, len(auth.Password), auth.Password))
}

func (c *Client) isActive() bool {
	return c.active.Load()
}

func (c *Client) send(payload []byte, skipResponses int, cb Callback) error {
	if !c.isActive() {
		c.reg.BackendUnavailable.WithLabelValues(c.addr, "inactive").Inc()
		return ErrBackendUnavailable
	}
	c.inflight.Add(1)
	c.reg.BackendInflight.Inc()
	c.reg.BackendInflightByNode.WithLabelValues(c.addr).Inc()
	req := request{payload: payload, cb: cb, start: time.Now(), skipResponses: skipResponses}
	select {
	case c.requests <- req:
		return nil
	case <-c.done:
		c.inflight.Add(-1)
		c.reg.BackendInflight.Dec()
		c.reg.BackendInflightByNode.WithLabelValues(c.addr).Dec()
		c.reg.BackendUnavailable.WithLabelValues(c.addr, "closed_before_write").Inc()
		return ErrBackendUnavailable
	default:
		c.inflight.Add(-1)
		c.reg.BackendInflight.Dec()
		c.reg.BackendInflightByNode.WithLabelValues(c.addr).Dec()
		c.reg.BackendUnavailable.WithLabelValues(c.addr, "inflight_limit").Inc()
		return errors.New("backend inflight limit exceeded")
	}
}

func (c *Client) writeLoop() {
	for {
		select {
		case req := <-c.requests:
			if _, err := c.conn.Write(req.payload); err != nil {
				c.complete(req, err, nil)
				c.close()
				continue
			}
			select {
			case c.pending <- req:
			case <-c.done:
				c.complete(req, ErrBackendUnavailable, nil)
				return
			}
		case <-c.done:
			return
		}
	}
}

func (c *Client) readLoop() {
	var current *request
	for {
		resp, err := protocol.ReadFrameRaw(c.br, c.maxResponseBytes)
		if err != nil {
			c.close()
			c.failQueued(err, current)
			return
		}
		if current == nil {
			select {
			case req := <-c.pending:
				current = &req
			case <-c.done:
				return
			}
		}
		if current.skipResponses > 0 {
			current.skipResponses--
			continue
		}
		c.complete(*current, nil, resp)
		current = nil
	}
}

func (c *Client) complete(req request, err error, resp []byte) {
	if err == nil {
		c.reg.BackendLatency.WithLabelValues(c.addr).Observe(time.Since(req.start).Seconds())
	}
	c.inflight.Add(-1)
	c.reg.BackendInflight.Dec()
	c.reg.BackendInflightByNode.WithLabelValues(c.addr).Dec()
	req.cb(Result{Response: resp, Err: err})
}

func (c *Client) failQueued(err error, current *request) {
	if current != nil {
		c.complete(*current, err, nil)
	}
	for {
		select {
		case req := <-c.pending:
			c.complete(req, err, nil)
		case req := <-c.requests:
			c.complete(req, err, nil)
		default:
			return
		}
	}
}

func (c *Client) close() {
	c.closeOnce.Do(func() {
		c.active.Store(false)
		if c.done != nil {
			close(c.done)
		}
		if c.conn != nil {
			_ = c.conn.Close()
		}
		if c.reg != nil {
			c.reg.BackendConns.Dec()
			c.reg.BackendConnsByNode.WithLabelValues(c.addr).Dec()
		}
	})
}

func (c *Client) shouldReconnectNow(now time.Time) bool {
	next := c.nextReconnectAt.Load()
	return next == 0 || now.UnixNano() >= next
}

func (c *Client) scheduleNextReconnect() {
	current := time.Duration(c.reconnectDelayMS.Load()) * time.Millisecond
	if current <= 0 {
		current = initialReconnectDelay
	}
	delay := withJitter(current)
	next := time.Now().Add(delay)
	c.nextReconnectAt.Store(next.UnixNano())
	nextBase := current * 2
	if nextBase > maxReconnectDelay {
		nextBase = maxReconnectDelay
	}
	c.reconnectDelayMS.Store(int64(nextBase / time.Millisecond))
}

func withJitter(base time.Duration) time.Duration {
	if base <= 0 {
		return initialReconnectDelay
	}
	jitterRange := max(time.Millisecond, base/5)
	return base + time.Duration(rand.Int63n(int64(jitterRange)))
}
