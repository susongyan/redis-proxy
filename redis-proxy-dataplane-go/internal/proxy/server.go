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

	"github.com/example/redis-proxy-dataplane-go/internal/backend"
	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
	"github.com/example/redis-proxy-dataplane-go/internal/router"
	"go.uber.org/zap"
)

type Server struct {
	cfg      *config.Config
	router   *router.Router
	backends *backend.Pools
	metrics  *metrics.Registry
	log      *zap.Logger
	ln       net.Listener
	done     chan struct{}
	wg       sync.WaitGroup
	clientID atomic.Uint64
}

type completion struct {
	seq      uint64
	command  string
	response []byte
	err      error
	start    time.Time
}

func NewServer(cfg *config.Config, rt *router.Router, pools *backend.Pools, reg *metrics.Registry, log *zap.Logger) *Server {
	return &Server{cfg: cfg, router: rt, backends: pools, metrics: reg, log: log, done: make(chan struct{})}
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
	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		defer closeClient()
		s.writeResponses(conn, completions, clientDone, &pending)
	}()
	finish := func() {
		closeClient()
		<-writerDone
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

		if int(pending.Load()) > s.cfg.Limits.MaxPipelineDepth {
			s.metrics.Errors.WithLabelValues("pipeline_limit").Inc()
			s.enqueueCompletion(completions, clientDone, completion{seq: current, command: cmd, err: errors.New("pipeline depth exceeded"), start: start})
			continue
		}

		addr, err := s.router.Route(req)
		if err != nil {
			s.metrics.Errors.WithLabelValues("route").Inc()
			s.enqueueCompletion(completions, clientDone, completion{seq: current, command: cmd, err: err, start: start})
			continue
		}

		err = s.backends.DoAsyncAffinity(addr, affinity, req.Raw, func(result backend.Result) {
			s.enqueueCompletion(completions, clientDone, completion{
				seq:      current,
				command:  cmd,
				response: result.Response,
				err:      result.Err,
				start:    start,
			})
		})
		if err != nil {
			s.metrics.Errors.WithLabelValues("backend").Inc()
			s.enqueueCompletion(completions, clientDone, completion{seq: current, command: cmd, err: err, start: start})
		}
	}
}

func (s *Server) writeResponses(conn net.Conn, completions <-chan completion, clientDone <-chan struct{}, pending *atomic.Int64) {
	next := uint64(0)
	buffered := map[uint64]completion{}
	for {
		select {
		case <-clientDone:
			return
		case item := <-completions:
			buffered[item.seq] = item
			for {
				current, ok := buffered[next]
				if !ok {
					break
				}
				delete(buffered, next)
				next++
				if !s.writeOne(conn, current, pending) {
					return
				}
			}
		}
	}
}

func (s *Server) writeOne(conn net.Conn, item completion, pending *atomic.Int64) bool {
	defer func() {
		pending.Add(-1)
		s.metrics.ClientPending.Dec()
	}()
	if item.err != nil {
		s.metrics.Errors.WithLabelValues("backend").Inc()
		_, err := conn.Write(protocol.ErrorFrame("backend unavailable"))
		return err == nil
	}
	if bytes.HasPrefix(item.response, []byte("-MOVED ")) {
		s.metrics.Moved.Inc()
		s.router.UpdateMoved(item.response, s.backends)
	}
	if bytes.HasPrefix(item.response, []byte("-ASK ")) {
		s.metrics.Ask.Inc()
	}
	if _, err := conn.Write(item.response); err != nil {
		s.metrics.Errors.WithLabelValues("client_write").Inc()
		return false
	}
	s.metrics.Latency.WithLabelValues(strings.ToUpper(item.command)).Observe(time.Since(item.start).Seconds())
	return true
}

func (s *Server) enqueueCompletion(ch chan<- completion, done <-chan struct{}, item completion) {
	select {
	case ch <- item:
	case <-done:
	}
}
