package proxy

import (
	"sync"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
)

type namespaceLimiter struct {
	mu         sync.Mutex
	reg        *metrics.Registry
	conns      map[string]int
	inflight   map[string]int
	qpsWindows map[string]qpsWindow
}

type qpsWindow struct {
	second int64
	count  int
}

func newNamespaceLimiter(reg *metrics.Registry) *namespaceLimiter {
	return &namespaceLimiter{
		reg:        reg,
		conns:      map[string]int{},
		inflight:   map[string]int{},
		qpsWindows: map[string]qpsWindow{},
	}
}

func (l *namespaceLimiter) bind(current string, next config.NamespaceConfig) (bool, string) {
	if next.Name == "" || current == next.Name {
		return true, ""
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if next.Limits.MaxConnections > 0 && l.conns[next.Name] >= next.Limits.MaxConnections {
		return false, "connection_limit"
	}
	if current != "" && l.conns[current] > 0 {
		l.conns[current]--
		l.reg.NamespaceConnections.WithLabelValues(current).Set(float64(l.conns[current]))
	}
	l.conns[next.Name]++
	l.reg.NamespaceConnections.WithLabelValues(next.Name).Set(float64(l.conns[next.Name]))
	return true, ""
}

func (l *namespaceLimiter) unbind(namespace string) {
	if namespace == "" {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.conns[namespace] > 0 {
		l.conns[namespace]--
	}
	l.reg.NamespaceConnections.WithLabelValues(namespace).Set(float64(l.conns[namespace]))
}

func (l *namespaceLimiter) allowRequest(namespace config.NamespaceConfig) (bool, string) {
	if namespace.Name == "" {
		return true, ""
	}
	now := time.Now().Unix()
	l.mu.Lock()
	defer l.mu.Unlock()
	if namespace.Limits.MaxQPS > 0 {
		window := l.qpsWindows[namespace.Name]
		if window.second != now {
			window = qpsWindow{second: now}
		}
		if window.count >= namespace.Limits.MaxQPS {
			l.qpsWindows[namespace.Name] = window
			return false, "qps_limit"
		}
		window.count++
		l.qpsWindows[namespace.Name] = window
	}
	if namespace.Limits.MaxInflight > 0 && l.inflight[namespace.Name] >= namespace.Limits.MaxInflight {
		return false, "inflight_limit"
	}
	l.inflight[namespace.Name]++
	l.reg.NamespaceInflight.WithLabelValues(namespace.Name).Set(float64(l.inflight[namespace.Name]))
	return true, ""
}

func (l *namespaceLimiter) finishRequest(namespace string) {
	if namespace == "" {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.inflight[namespace] > 0 {
		l.inflight[namespace]--
	}
	l.reg.NamespaceInflight.WithLabelValues(namespace).Set(float64(l.inflight[namespace]))
}
