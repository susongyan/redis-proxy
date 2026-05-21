package proxy

import (
	"testing"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/prometheus/client_golang/prometheus/testutil"
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

func TestNamespaceLimiterObservesLimitConfig(t *testing.T) {
	reg := metrics.NewRegistry()
	limiter := newNamespaceLimiter(reg)
	namespace := config.NamespaceConfig{Name: "app-a", Limits: config.NamespaceLimitsConfig{MaxConnections: 2, MaxQPS: 3, MaxInflight: 4}}
	if ok, reason := limiter.bind("", namespace); !ok || reason != "" {
		t.Fatalf("bind ok=%v reason=%q", ok, reason)
	}
	if got := testutil.ToFloat64(reg.NamespaceLimitConfig.WithLabelValues("app-a", "connections")); got != 2 {
		t.Fatalf("connection limit config=%v want 2", got)
	}
	if got := testutil.ToFloat64(reg.NamespaceLimitConfig.WithLabelValues("app-a", "qps")); got != 3 {
		t.Fatalf("qps limit config=%v want 3", got)
	}
	if got := testutil.ToFloat64(reg.NamespaceLimitConfig.WithLabelValues("app-a", "inflight")); got != 4 {
		t.Fatalf("inflight limit config=%v want 4", got)
	}
}
