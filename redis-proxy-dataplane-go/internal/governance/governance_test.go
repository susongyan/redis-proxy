package governance

import (
	"testing"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
)

func TestAuthenticateNamespaceToken(t *testing.T) {
	cfg := testConfig()
	result := Authenticate(cfg, req("AUTH", "app-a", "token-a"))
	if !result.Allowed || result.Namespace != "app-a" || result.Result != "success" {
		t.Fatalf("auth result=%+v", result)
	}
	result = Authenticate(cfg, req("AUTH", "app-a", "bad"))
	if result.Allowed || result.Result != "invalid_token" {
		t.Fatalf("auth bad token result=%+v", result)
	}
	result = Authenticate(cfg, req("AUTH", "missing", "token"))
	if result.Allowed || result.Result != "unknown_namespace" {
		t.Fatalf("auth unknown namespace result=%+v", result)
	}
}

func TestEvaluateRequiresAuthentication(t *testing.T) {
	decision := Evaluate(testConfig(), "", req("GET", "app-a:1"))
	if decision.Action != NoAuth || decision.Reason != "unauthenticated" {
		t.Fatalf("decision=%+v", decision)
	}
}

func TestEvaluateCommandDenyAndWarn(t *testing.T) {
	cfg := testConfig()
	decision := Evaluate(cfg, "app-a", req("FLUSHALL"))
	if decision.Action != Deny || decision.Reason != "global_denied_command" {
		t.Fatalf("decision=%+v", decision)
	}
	decision = Evaluate(cfg, "app-a", req("KEYS", "app-a:*"))
	if decision.Action != Allow || !decision.Warn {
		t.Fatalf("decision=%+v", decision)
	}
}

func TestEvaluateReadOnlyNamespace(t *testing.T) {
	cfg := testConfig()
	decision := Evaluate(cfg, "reader", req("GET", "reader:1"))
	if decision.Action != Allow {
		t.Fatalf("read decision=%+v", decision)
	}
	decision = Evaluate(cfg, "reader", req("SET", "reader:1", "v"))
	if decision.Action != Deny || decision.Reason != "readonly" {
		t.Fatalf("write decision=%+v", decision)
	}
}

func TestEvaluateAllowedKeyPrefixes(t *testing.T) {
	cfg := testConfig()
	if decision := Evaluate(cfg, "app-a", req("MSET", "app-a:1", "v1", "app-a:2", "v2")); decision.Action != Allow {
		t.Fatalf("allowed decision=%+v", decision)
	}
	if decision := Evaluate(cfg, "app-a", req("GET", "other:1")); decision.Action != Deny || decision.Reason != "key_prefix" {
		t.Fatalf("denied decision=%+v", decision)
	}
	if decision := Evaluate(cfg, "app-a", req("SCAN", "0")); decision.Action != Unsupported {
		t.Fatalf("unsupported decision=%+v", decision)
	}
}

func testConfig() config.GovernanceConfig {
	return config.GovernanceConfig{
		Enabled:     true,
		RequireAuth: true,
		CommandPolicy: config.CommandPolicyConfig{
			DeniedCommands:   []string{"FLUSHALL", "FLUSHDB"},
			WarnOnlyCommands: []string{"KEYS"},
		},
		Namespaces: []config.NamespaceConfig{
			{Name: "app-a", Token: "token-a", AllowedKeyPrefixes: []string{"app-a:"}},
			{Name: "reader", Token: "token-r", ReadOnly: true, AllowedKeyPrefixes: []string{"reader:"}},
		},
	}
}

func req(args ...string) protocol.Request {
	values := make([][]byte, 0, len(args))
	for _, arg := range args {
		values = append(values, []byte(arg))
	}
	return protocol.Request{Args: values}
}
