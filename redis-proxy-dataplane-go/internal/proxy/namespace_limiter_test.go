package proxy

import (
	"testing"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
)

func TestNamespaceLimiterConnectionLimit(t *testing.T) {
	limiter := newNamespaceLimiter(metrics.NewRegistry())
	namespace := config.NamespaceConfig{Name: "app-a", Limits: config.NamespaceLimitsConfig{MaxConnections: 1}}
	if ok, reason := limiter.bind("", namespace); !ok || reason != "" {
		t.Fatalf("first bind ok=%v reason=%q", ok, reason)
	}
	if ok, reason := limiter.bind("", namespace); ok || reason != "connection_limit" {
		t.Fatalf("second bind ok=%v reason=%q", ok, reason)
	}
	limiter.unbind("app-a")
	if ok, reason := limiter.bind("", namespace); !ok || reason != "" {
		t.Fatalf("bind after unbind ok=%v reason=%q", ok, reason)
	}
}

func TestNamespaceLimiterQPSAndInflightLimit(t *testing.T) {
	limiter := newNamespaceLimiter(metrics.NewRegistry())
	namespace := config.NamespaceConfig{Name: "app-a", Limits: config.NamespaceLimitsConfig{MaxQPS: 1, MaxInflight: 1}}
	if ok, reason := limiter.allowRequest(namespace); !ok || reason != "" {
		t.Fatalf("first request ok=%v reason=%q", ok, reason)
	}
	if ok, reason := limiter.allowRequest(namespace); ok || reason != "qps_limit" {
		t.Fatalf("second request ok=%v reason=%q", ok, reason)
	}
	limiter.finishRequest("app-a")
}

func TestNamespaceLimiterInflightLimit(t *testing.T) {
	limiter := newNamespaceLimiter(metrics.NewRegistry())
	namespace := config.NamespaceConfig{Name: "app-a", Limits: config.NamespaceLimitsConfig{MaxInflight: 1}}
	if ok, reason := limiter.allowRequest(namespace); !ok || reason != "" {
		t.Fatalf("first request ok=%v reason=%q", ok, reason)
	}
	if ok, reason := limiter.allowRequest(namespace); ok || reason != "inflight_limit" {
		t.Fatalf("second request ok=%v reason=%q", ok, reason)
	}
	limiter.finishRequest("app-a")
	if ok, reason := limiter.allowRequest(namespace); !ok || reason != "" {
		t.Fatalf("request after finish ok=%v reason=%q", ok, reason)
	}
}
