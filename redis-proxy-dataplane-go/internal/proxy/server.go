package proxy

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/analysis"
	"github.com/example/redis-proxy-dataplane-go/internal/backend"
	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/governance"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
	"github.com/example/redis-proxy-dataplane-go/internal/router"
	"go.uber.org/zap"
)

type Server struct {
	cfg         *config.Config
	router      *router.Manager
	backends    *backend.Pools
	metrics     *metrics.Registry
	log         *zap.Logger
	ln          net.Listener
	done        chan struct{}
	wg          sync.WaitGroup
	clientID    atomic.Uint64
	onMoved     func()
	nsLimiter   *namespaceLimiter
	keyGov      *keyGovernanceLimiter
	hotKeys     *analysis.HotKeyTracker
	largeKeys   *analysis.LargeKeyTracker
	slowQueries *analysis.SlowQueryTracker
}

type completion struct {
	seq         uint64
	command     string
	response    []byte
	err         error
	start       time.Time
	backend     time.Duration
	completedAt time.Time
	largeCtx    analysis.LargeKeyContext
	slowCtx     analysis.SlowQueryContext
}

func NewServer(cfg *config.Config, rt *router.Manager, pools *backend.Pools, reg *metrics.Registry, log *zap.Logger, onMoved func(), hotKeys *analysis.HotKeyTracker, largeKeys *analysis.LargeKeyTracker, slowQueries *analysis.SlowQueryTracker) *Server {
	return &Server{cfg: cfg, router: rt, backends: pools, metrics: reg, log: log, done: make(chan struct{}), onMoved: onMoved, nsLimiter: newNamespaceLimiter(reg), keyGov: newKeyGovernanceLimiter(reg), hotKeys: hotKeys, largeKeys: largeKeys, slowQueries: slowQueries}
}

func (s *Server) ListenAndServe(ctx context.Context) error {
	ln, err := net.Listen("tcp", s.cfg.Server.Listen)
	if err != nil {
		return err
	}
	s.ln = ln
	go func() {
		<-ctx.Done()
		s.Shutdown()
	}()
	for {
		conn, err := ln.Accept()
		if err != nil {
			select {
			case <-s.done:
				return nil
			default:
				return err
			}
		}
		s.wg.Add(1)
		s.metrics.ActiveConns.Inc()
		go s.handle(conn)
	}
}

func (s *Server) Shutdown() {
	select {
	case <-s.done:
		return
	default:
		close(s.done)
	}
	if s.ln != nil {
		_ = s.ln.Close()
	}
	s.wg.Wait()
}

func (s *Server) handle(conn net.Conn) {
	defer s.wg.Done()
	defer s.metrics.ActiveConns.Dec()
	defer conn.Close()

	clientDone := make(chan struct{})
	completions := make(chan completion, max(1, s.cfg.Limits.MaxPipelineDepth))
	var closeOnce sync.Once
	closeClient := func() {
		closeOnce.Do(func() {
			close(clientDone)
			_ = conn.Close()
		})
	}

	var pending atomic.Int64
	namespace := ""
	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		defer closeClient()
		s.writeResponses(conn, completions, clientDone, &pending)
	}()
	finish := func() {
		closeClient()
		<-writerDone
		s.nsLimiter.unbind(namespace)
		for remaining := pending.Swap(0); remaining > 0; remaining-- {
			s.metrics.ClientPending.Dec()
		}
	}

	br := bufio.NewReader(conn)
	var seq uint64
	affinity := s.clientID.Add(1)
	for {
		select {
		case <-s.done:
			finish()
			return
		case <-clientDone:
			finish()
			return
		default:
		}
		_ = conn.SetReadDeadline(time.Now().Add(5 * time.Minute))
		req, err := protocol.ReadRequest(br, s.cfg.Limits.MaxRequestBytes)
		if err != nil {
			if !errors.Is(err, io.EOF) && !errors.Is(err, net.ErrClosed) {
				s.metrics.Errors.WithLabelValues("request_parse").Inc()
			}
			finish()
			return
		}

		start := time.Now()
		cmd := req.Command()
		current := seq
		seq++
		s.metrics.Requests.WithLabelValues(cmd).Inc()
		pending.Add(1)
		s.metrics.ClientPending.Inc()

		if int(pending.Load()) > s.router.Limits().MaxPipelineDepth {
			s.metrics.Errors.WithLabelValues("pipeline_limit").Inc()
			s.enqueueCompletion(completions, clientDone, completion{seq: current, command: cmd, response: protocol.ErrorFrame("pipeline depth exceeded"), start: start})
			continue
		}

		govCfg := s.router.Governance()
		if govCfg.Enabled && cmd == "AUTH" {
			auth := governance.Authenticate(govCfg, req)
			s.metrics.Auth.WithLabelValues(auth.Namespace, auth.Result).Inc()
			if auth.Allowed {
				next, _ := governance.Namespace(govCfg, auth.Namespace)
				if ok, reason := s.nsLimiter.bind(namespace, next); !ok {
					s.metrics.Auth.WithLabelValues(auth.Namespace, reason).Inc()
					s.metrics.GovernanceRejects.WithLabelValues(auth.Namespace, cmd, reason).Inc()
					s.metrics.NamespaceLimitRejects.WithLabelValues(auth.Namespace, "connections").Inc()
					s.enqueueCompletion(completions, clientDone, completion{seq: current, command: cmd, response: []byte("-ERR namespace connection limit exceeded\r\n"), start: start})
					continue
				}
				namespace = auth.Namespace
			}
			s.enqueueCompletion(completions, clientDone, completion{seq: current, command: cmd, response: auth.Response, start: start})
			continue
		}
		govDecision := governance.Evaluate(govCfg, namespace, req)
		if govDecision.Warn {
			s.metrics.GovernanceWarns.WithLabelValues(govDecision.Namespace, cmd, govDecision.WarnReason).Inc()
		}
		if govDecision.Action != governance.Allow {
			s.metrics.GovernanceRejects.WithLabelValues(govDecision.Namespace, cmd, govDecision.Reason).Inc()
			s.enqueueCompletion(completions, clientDone, completion{seq: current, command: cmd, response: govDecision.Response, start: start})
			continue
		}
		namespaceCfg, _ := governance.Namespace(govCfg, namespace)
		if ok, reason := s.nsLimiter.allowRequest(namespaceCfg); !ok {
			s.metrics.GovernanceRejects.WithLabelValues(namespace, cmd, reason).Inc()
			s.metrics.NamespaceLimitRejects.WithLabelValues(namespace, namespaceLimitLabel(reason)).Inc()
			s.enqueueCompletion(completions, clientDone, completion{seq: current, command: cmd, response: []byte("-ERR request limited by proxy governance\r\n"), start: start})
			continue
		}
		keyDecision := s.keyGov.evaluate(govCfg, namespaceCfg, req)
		if !keyDecision.Allowed {
			s.nsLimiter.finishRequest(namespace)
			s.metrics.KeyGovernanceRejects.WithLabelValues(namespace, keyDecision.Rule, cmd, keyDecision.Reason).Inc()
			s.enqueueCompletion(completions, clientDone, completion{seq: current, command: cmd, response: keyDecision.Response, start: start})
			continue
		}
		s.hotKeys.Observe(namespace, req)
		largeCtx := s.largeKeys.Context(namespace, req)
		s.largeKeys.ObserveRequest(largeCtx, len(req.Raw))
		slowCtx := s.slowQueries.Context(namespace, req)
		requestNamespace := namespace

		decision, err := s.router.RouteDecisionForNamespace(requestNamespace, req)
		if err != nil {
			s.nsLimiter.finishRequest(requestNamespace)
			s.metrics.Errors.WithLabelValues("route").Inc()
			s.enqueueCompletion(completions, clientDone, completion{seq: current, command: cmd, err: err, start: start})
			continue
		}
		s.metrics.RouteDecisions.WithLabelValues(decision.Cluster, decision.Rule).Inc()

		backendStart := time.Now()
		requestAffinity := s.requestAffinity(affinity, req)
		err = s.backends.DoAsyncAffinity(decision.Addr, requestAffinity, req.Raw, func(result backend.Result) {
			s.nsLimiter.finishRequest(requestNamespace)
			s.completeBackendResult(completions, clientDone, current, cmd, start, backendStart, decision, requestAffinity, req.Raw, result, false, largeCtx, slowCtx)
		})
		if err != nil {
			s.nsLimiter.finishRequest(requestNamespace)
			s.metrics.Errors.WithLabelValues("backend").Inc()
			s.enqueueCompletion(completions, clientDone, completion{seq: current, command: cmd, err: err, start: start, backend: time.Since(backendStart), slowCtx: slowCtx})
		}
	}
}

func namespaceLimitLabel(reason string) string {
	switch reason {
	case "connection_limit":
		return "connections"
	case "qps_limit":
		return "qps"
	case "inflight_limit":
		return "inflight"
	default:
		return reason
	}
}

func (s *Server) completeBackendResult(completions chan<- completion, clientDone <-chan struct{}, seq uint64, command string, start time.Time, backendStart time.Time, decision router.Decision, affinity uint64, raw []byte, result backend.Result, askRetried bool, largeCtx analysis.LargeKeyContext, slowCtx analysis.SlowQueryContext) {
	if result.Err != nil || !bytes.HasPrefix(result.Response, []byte("-ASK ")) {
		s.enqueueCompletion(completions, clientDone, completion{seq: seq, command: command, response: result.Response, err: result.Err, start: start, backend: time.Since(backendStart), largeCtx: largeCtx, slowCtx: slowCtx})
		return
	}
	if askRetried {
		s.enqueueCompletion(completions, clientDone, completion{seq: seq, command: command, response: result.Response, start: start, backend: time.Since(backendStart), largeCtx: largeCtx, slowCtx: slowCtx})
		return
	}
	s.metrics.Ask.Inc()
	addr, err := s.router.AskTarget(result.Response, decision.Cluster, s.backends)
	if err != nil {
		s.metrics.AskRedirects.WithLabelValues("error").Inc()
		s.enqueueCompletion(completions, clientDone, completion{seq: seq, command: command, err: err, start: start, backend: time.Since(backendStart), slowCtx: slowCtx})
		return
	}
	if err := s.backends.DoAsyncAsking(addr, affinity, raw, func(retry backend.Result) {
		if retry.Err != nil {
			s.metrics.AskRedirects.WithLabelValues("error").Inc()
		} else if bytes.HasPrefix(retry.Response, []byte("-ASK ")) {
			s.metrics.AskRedirects.WithLabelValues("loop_prevented").Inc()
		} else {
			s.metrics.AskRedirects.WithLabelValues("success").Inc()
		}
		s.completeBackendResult(completions, clientDone, seq, command, start, backendStart, decision, affinity, raw, retry, true, largeCtx, slowCtx)
	}); err != nil {
		s.metrics.AskRedirects.WithLabelValues("error").Inc()
		s.enqueueCompletion(completions, clientDone, completion{seq: seq, command: command, err: err, start: start, backend: time.Since(backendStart), slowCtx: slowCtx})
	}
}

func (s *Server) writeResponses(conn net.Conn, completions <-chan completion, clientDone <-chan struct{}, pending *atomic.Int64) {
	next := uint64(0)
	buffered := map[uint64]completion{}
	batchSize := max(1, s.router.Limits().PipelineFlushBatchSize)
	maxDelay := time.Duration(s.router.Limits().PipelineFlushMaxDelayMillis) * time.Millisecond
	for {
		select {
		case <-clientDone:
			return
		case item := <-completions:
			buffered[item.seq] = item
			if item.seq > next {
				s.metrics.ObservePipelineHOLBlocked("backend_pending", len(buffered))
			}
			s.metrics.ObservePipelineBuffered(len(buffered))
			current, ok := buffered[next]
			if !ok {
				continue
			}
			delete(buffered, next)
			next++
			batch, ok := s.collectResponseBatch(current, &next, buffered, completions, clientDone, batchSize, maxDelay)
			if !ok {
				return
			}
			s.metrics.ObservePipelineBuffered(len(buffered))
			if !s.writeBatch(conn, batch, pending) {
				return
			}
		}
	}
}

func (s *Server) collectResponseBatch(first completion, next *uint64, buffered map[uint64]completion, completions <-chan completion, clientDone <-chan struct{}, batchSize int, maxDelay time.Duration) ([]completion, bool) {
	batch := []completion{first}
	for len(batch) < batchSize {
		current, ok := buffered[*next]
		if !ok {
			break
		}
		delete(buffered, *next)
		*next = *next + 1
		batch = append(batch, current)
	}
	if len(batch) >= batchSize || maxDelay <= 0 {
		return batch, true
	}
	timer := time.NewTimer(maxDelay)
	defer timer.Stop()
	for len(batch) < batchSize {
		select {
		case <-clientDone:
			return nil, false
		case <-timer.C:
			return batch, true
		case item := <-completions:
			buffered[item.seq] = item
			if item.seq > *next {
				s.metrics.ObservePipelineHOLBlocked("backend_pending", len(buffered))
			}
			for len(batch) < batchSize {
				current, ok := buffered[*next]
				if !ok {
					break
				}
				delete(buffered, *next)
				*next = *next + 1
				batch = append(batch, current)
			}
		}
	}
	return batch, true
}

func (s *Server) writeBatch(conn net.Conn, batch []completion, pending *atomic.Int64) bool {
	var out bytes.Buffer
	for _, item := range batch {
		payload := s.prepareResponse(item, pending)
		if len(payload) > 0 {
			out.Write(payload)
		}
	}
	s.metrics.ObservePipelineFlushBatch(len(batch))
	if out.Len() == 0 {
		return true
	}
	if _, err := conn.Write(out.Bytes()); err != nil {
		s.metrics.Errors.WithLabelValues("client_write").Inc()
		return false
	}
	return true
}

func (s *Server) prepareResponse(item completion, pending *atomic.Int64) []byte {
	pending.Add(-1)
	s.metrics.ClientPending.Dec()
	if !item.completedAt.IsZero() {
		s.metrics.ObservePipelineHOLWait(time.Since(item.completedAt).Milliseconds())
	}
	if item.err != nil {
		s.metrics.Errors.WithLabelValues("backend").Inc()
		s.slowQueries.Observe(item.slowCtx, time.Since(item.start), item.backend)
		return protocol.ErrorFrame("backend unavailable")
	}
	if bytes.HasPrefix(item.response, []byte("-MOVED ")) {
		s.metrics.Moved.Inc()
		s.router.UpdateMoved(item.response, s.backends)
		if s.onMoved != nil {
			s.onMoved()
		}
	}
	if bytes.HasPrefix(item.response, []byte("-ASK ")) {
		s.metrics.Ask.Inc()
	}
	s.observeResponseSize(item.command, len(item.response))
	s.largeKeys.ObserveResponse(item.largeCtx, len(item.response))
	s.slowQueries.Observe(item.slowCtx, time.Since(item.start), item.backend)
	s.metrics.Latency.WithLabelValues(strings.ToUpper(item.command)).Observe(time.Since(item.start).Seconds())
	return item.response
}

func (s *Server) observeResponseSize(command string, size int) {
	normalized := strings.ToUpper(command)
	s.metrics.ResponseBytes.WithLabelValues(normalized).Observe(float64(size))
	threshold := s.router.Limits().LargeResponseBytes
	if threshold > 0 && size >= threshold {
		s.metrics.LargeResponses.WithLabelValues(normalized).Inc()
	}
}

func (s *Server) enqueueCompletion(ch chan<- completion, done <-chan struct{}, item completion) {
	item.completedAt = time.Now()
	select {
	case ch <- item:
	case <-done:
	}
}

func (s *Server) requestAffinity(clientAffinity uint64, req protocol.Request) uint64 {
	strategy := s.router.BackendAffinityStrategy()
	key, ok := router.RawKey(req.Args)
	switch strategy {
	case "keySlot", "hashTag":
		if ok {
			return uint64(router.Slot(key))
		}
	}
	return clientAffinity
}
