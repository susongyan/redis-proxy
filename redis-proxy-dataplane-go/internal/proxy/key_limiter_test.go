package proxy

import (
	"testing"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
)

func TestKeyGovernanceDisabledKey(t *testing.T) {
	limiter := newKeyGovernanceLimiter()
	decision := limiter.evaluate(testGovernance(), testNamespace(), request("GET", "app-a:blocked"))
	if decision.Allowed || decision.Reason != "exact_key_disabled" {
		t.Fatalf("decision=%+v", decision)
	}
}

func TestKeyGovernanceRuleDisabled(t *testing.T) {
	limiter := newKeyGovernanceLimiter()
	decision := limiter.evaluate(testGovernance(), testNamespace(), request("GET", "app-a:disabled:1"))
	if decision.Allowed || decision.Rule != "disabled-prefix" || decision.Reason != "rule_disabled" {
		t.Fatalf("decision=%+v", decision)
	}
}

func TestKeyGovernanceSlidingWindowLimitAndRecovery(t *testing.T) {
	limiter := newKeyGovernanceLimiter()
	now := time.UnixMilli(0)
	limiter.now = func() time.Time { return now }
	cfg := testGovernance()
	namespace := testNamespace()

	if decision := limiter.evaluate(cfg, namespace, request("GET", "app-a:hot:1")); !decision.Allowed {
		t.Fatalf("first decision=%+v", decision)
	}
	if decision := limiter.evaluate(cfg, namespace, request("GET", "app-a:hot:2")); decision.Allowed || decision.Reason != "qps_limit" {
		t.Fatalf("second decision=%+v", decision)
	}
	now = now.Add(1100 * time.Millisecond)
	if decision := limiter.evaluate(cfg, namespace, request("GET", "app-a:hot:3")); !decision.Allowed {
		t.Fatalf("after window decision=%+v", decision)
	}
}

func TestKeyGovernanceMultiKeyRejectsWholeCommand(t *testing.T) {
	limiter := newKeyGovernanceLimiter()
	decision := limiter.evaluate(testGovernance(), testNamespace(), request("MGET", "app-a:1", "app-a:blocked"))
	if decision.Allowed || decision.Reason != "exact_key_disabled" {
		t.Fatalf("decision=%+v", decision)
	}
}

func TestKeyGovernanceUnsupportedCommandFailsClosed(t *testing.T) {
	limiter := newKeyGovernanceLimiter()
	decision := limiter.evaluate(testGovernance(), testNamespace(), request("SCAN", "0"))
	if decision.Allowed || decision.Reason != "key_policy_unsupported" {
		t.Fatalf("decision=%+v", decision)
	}
}

func testGovernance() config.GovernanceConfig {
	return config.GovernanceConfig{
		Enabled:              true,
		RequireAuth:          true,
		KeyLimitWindowMillis: 1000,
		KeyLimitBucketMillis: 100,
	}
}

func testNamespace() config.NamespaceConfig {
	return config.NamespaceConfig{
		Name:         "app-a",
		DisabledKeys: []string{"app-a:blocked"},
		KeyRules: []config.KeyRuleConfig{
			{Name: "disabled-prefix", KeyPrefix: "app-a:disabled:", Disabled: true},
			{Name: "hot-prefix", KeyPrefix: "app-a:hot:", MaxQPS: 1},
		},
	}
}

func request(args ...string) protocol.Request {
	values := make([][]byte, 0, len(args))
	for _, arg := range args {
		values = append(values, []byte(arg))
	}
	return protocol.Request{Args: values}
}
