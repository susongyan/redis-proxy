package backend

import (
	"bufio"
	"errors"
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

type Result struct {
	Response []byte
	Err      error
}

type Callback func(Result)

type request struct {
	payload []byte
	cb      Callback
	start   time.Time
}

type Client struct {
	addr             string
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
}

type Pools struct {
	mu    sync.RWMutex
	conns map[string][]*Client
	reg   *metrics.Registry
	log   *zap.Logger
}

func NewPools(cfg *config.Config, reg *metrics.Registry, log *zap.Logger) (*Pools, error) {
	p := &Pools{conns: map[string][]*Client{}, reg: reg, log: log}
	for _, cluster := range cfg.Backends.Clusters {
		size := cluster.Pool.ConnectionsPerNode
		if size <= 0 {
			size = 8
		}
		maxInflight := cluster.Pool.MaxInflightPerConnection
		if maxInflight <= 0 {
			maxInflight = 1024
		}
		for _, node := range cluster.Nodes {
			if _, ok := p.conns[node]; ok {
				continue
			}
			clients := make([]*Client, 0, size)
			for i := 0; i < size; i++ {
				client, err := newClient(node, maxInflight, cfg.Limits.MaxResponseBytes, reg, log)
				if err != nil {
					p.Close()
					return nil, err
				}
				clients = append(clients, client)
			}
			p.conns[node] = clients
		}
	}
	return p, nil
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
		return ErrBackendUnavailable
	}
	return client.send(payload, cb)
}

func (p *Pools) DoAsyncAffinity(addr string, affinity uint64, payload []byte, cb Callback) error {
	client := p.selectClientByAffinity(addr, affinity)
	if client == nil {
		return ErrBackendUnavailable
	}
	return client.send(payload, cb)
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

func (p *Pools) Close() {
	p.mu.RLock()
	defer p.mu.RUnlock()
	for _, clients := range p.conns {
		for _, client := range clients {
			client.close()
		}
	}
}

func newClient(addr string, maxInflight int, maxResponseBytes int, reg *metrics.Registry, log *zap.Logger) (*Client, error) {
	conn, err := net.DialTimeout("tcp", addr, 2*time.Second)
	if err != nil {
		return nil, err
	}
	client := &Client{
		addr:             addr,
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
	reg.BackendConns.Inc()
	go client.writeLoop()
	go client.readLoop()
	return client, nil
}

func (c *Client) isActive() bool {
	return c.active.Load()
}

func (c *Client) send(payload []byte, cb Callback) error {
	if !c.isActive() {
		return ErrBackendUnavailable
	}
	c.inflight.Add(1)
	c.reg.BackendInflight.Inc()
	req := request{payload: payload, cb: cb, start: time.Now()}
	select {
	case c.requests <- req:
		return nil
	case <-c.done:
		c.inflight.Add(-1)
		c.reg.BackendInflight.Dec()
		return ErrBackendUnavailable
	default:
		c.inflight.Add(-1)
		c.reg.BackendInflight.Dec()
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
	for {
		resp, err := protocol.ReadFrameRaw(c.br, c.maxResponseBytes)
		if err != nil {
			c.close()
			c.failQueued(err)
			return
		}
		select {
		case req := <-c.pending:
			c.complete(req, nil, resp)
		case <-c.done:
			return
		}
	}
}

func (c *Client) complete(req request, err error, resp []byte) {
	if err == nil {
		c.reg.BackendLatency.WithLabelValues(c.addr).Observe(time.Since(req.start).Seconds())
	}
	c.inflight.Add(-1)
	c.reg.BackendInflight.Dec()
	req.cb(Result{Response: resp, Err: err})
}

func (c *Client) failQueued(err error) {
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
		close(c.done)
		_ = c.conn.Close()
		c.reg.BackendConns.Dec()
	})
}
