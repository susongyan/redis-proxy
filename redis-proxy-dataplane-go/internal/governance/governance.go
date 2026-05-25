package governance

import (
	"bytes"
	"strings"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
)

const (
	Allow       = "allow"
	Deny        = "deny"
	NoAuth      = "noauth"
	Unsupported = "unsupported"
)

type Decision struct {
	Action     string
	Response   []byte
	Reason     string
	Namespace  string
	Warn       bool
	WarnReason string
}

type AuthResult struct {
	Allowed   bool
	Namespace string
	Response  []byte
	Result    string
}

func Authenticate(cfg config.GovernanceConfig, req protocol.Request) AuthResult {
	if !cfg.Enabled {
		return AuthResult{Allowed: false, Result: "disabled"}
	}
	if len(req.Args) != 3 {
		return AuthResult{Allowed: false, Result: "invalid", Response: []byte("-ERR AUTH requires namespace and token\r\n")}
	}
	namespace := string(req.Args[1])
	token := string(req.Args[2])
	if candidate, ok := Namespace(cfg, namespace); ok {
		if candidate.Token == token {
			return AuthResult{Allowed: true, Namespace: namespace, Result: "success", Response: []byte("+OK\r\n")}
		}
		return AuthResult{Allowed: false, Namespace: namespace, Result: "invalid_token", Response: []byte("-ERR invalid namespace credentials\r\n")}
	}
	return AuthResult{Allowed: false, Namespace: namespace, Result: "unknown_namespace", Response: []byte("-ERR invalid namespace credentials\r\n")}
}

func Evaluate(cfg config.GovernanceConfig, namespaceName string, req protocol.Request) Decision {
	if !cfg.Enabled {
		return Decision{Action: Allow, Namespace: namespaceName}
	}
	command := req.Command()
	if command == "AUTH" || command == "QUIT" {
		return Decision{Action: Allow, Namespace: namespaceName}
	}
	if cfg.RequireAuth && namespaceName == "" {
		return Decision{Action: NoAuth, Response: []byte("-NOAUTH Authentication required\r\n"), Reason: "unauthenticated"}
	}
	namespace, ok := findNamespace(cfg, namespaceName)
	if cfg.RequireAuth && !ok {
		return Decision{Action: NoAuth, Namespace: namespaceName, Response: []byte("-NOAUTH namespace disabled\r\n"), Reason: "namespace_disabled"}
	}
	if commandSet(cfg.CommandPolicy.DeniedCommands)[command] {
		return Decision{Action: Deny, Namespace: namespaceName, Response: []byte("-ERR command denied by proxy governance\r\n"), Reason: "global_denied_command"}
	}
	if ok && commandSet(namespace.DeniedCommands)[command] {
		return Decision{Action: Deny, Namespace: namespaceName, Response: []byte("-ERR command denied by proxy governance\r\n"), Reason: "namespace_denied_command"}
	}
	decision := Decision{Action: Allow, Namespace: namespaceName}
	if commandSet(cfg.CommandPolicy.WarnOnlyCommands)[command] || (ok && commandSet(namespace.WarnOnlyCommands)[command]) {
		decision.Warn = true
		decision.WarnReason = "warn_only_command"
		return decision
	}
	if ok && namespace.ReadOnly && !isReadCommand(command) {
		return Decision{Action: Deny, Namespace: namespaceName, Response: []byte("-ERR command denied by proxy governance\r\n"), Reason: "readonly"}
	}
	if ok && len(namespace.AllowedKeyPrefixes) > 0 {
		keys, supported := Keys(req)
		if !supported {
			return Decision{Action: Unsupported, Namespace: namespaceName, Response: []byte("-ERR command key policy unsupported\r\n"), Reason: "key_policy_unsupported"}
		}
		for _, key := range keys {
			if !hasAllowedPrefix(key, namespace.AllowedKeyPrefixes) {
				return Decision{Action: Deny, Namespace: namespaceName, Response: []byte("-ERR key denied by proxy governance\r\n"), Reason: "key_prefix"}
			}
		}
	}
	return decision
}

func Summary(cfg config.GovernanceConfig) map[string]any {
	namespaces := make([]map[string]any, 0, len(cfg.Namespaces))
	for _, namespace := range cfg.Namespaces {
		namespaces = append(namespaces, map[string]any{
			"name":               namespace.Name,
			"readOnly":           namespace.ReadOnly,
			"allowedKeyPrefixes": append([]string(nil), namespace.AllowedKeyPrefixes...),
			"deniedCommands":     append([]string(nil), namespace.DeniedCommands...),
			"warnOnlyCommands":   append([]string(nil), namespace.WarnOnlyCommands...),
			"limits": map[string]int{
				"maxConnections": namespace.Limits.MaxConnections,
				"maxQps":         namespace.Limits.MaxQPS,
				"maxInflight":    namespace.Limits.MaxInflight,
			},
			"disabledKeys": append([]string(nil), namespace.DisabledKeys...),
			"keyRules":     append([]config.KeyRuleConfig(nil), namespace.KeyRules...),
		})
	}
	return map[string]any{
		"enabled":              cfg.Enabled,
		"requireAuth":          cfg.RequireAuth,
		"keyLimitWindowMillis": cfg.KeyLimitWindowMillis,
		"keyLimitBucketMillis": cfg.KeyLimitBucketMillis,
		"commandPolicy": map[string]any{
			"deniedCommands":   append([]string(nil), cfg.CommandPolicy.DeniedCommands...),
			"warnOnlyCommands": append([]string(nil), cfg.CommandPolicy.WarnOnlyCommands...),
		},
		"namespaces": namespaces,
	}
}

func HasKeyGovernance(namespace config.NamespaceConfig) bool {
	return len(namespace.DisabledKeys) > 0 || len(namespace.KeyRules) > 0
}

func Keys(req protocol.Request) ([][]byte, bool) {
	args := req.Args
	command := req.Command()
	if len(args) < 2 {
		return nil, false
	}
	switch command {
	case "GET", "SET", "EXPIRE", "PEXPIRE", "TTL", "PTTL", "HGET", "HSET", "HDEL", "LPUSH", "RPUSH", "LPOP", "RPOP", "SADD", "SREM", "SMEMBERS", "ZADD", "ZREM", "ZRANGE":
		return [][]byte{args[1]}, true
	case "DEL", "EXISTS", "MGET":
		return cloneKeys(args[1:]), true
	case "MSET":
		if (len(args)-1)%2 != 0 {
			return nil, false
		}
		keys := make([][]byte, 0, (len(args)-1)/2)
		for i := 1; i < len(args); i += 2 {
			keys = append(keys, args[i])
		}
		return keys, true
	default:
		return nil, false
	}
}

func findNamespace(cfg config.GovernanceConfig, name string) (config.NamespaceConfig, bool) {
	return Namespace(cfg, name)
}

func Namespace(cfg config.GovernanceConfig, name string) (config.NamespaceConfig, bool) {
	return cfg.NamespaceByName(name)
}

func commandSet(commands []string) map[string]bool {
	result := make(map[string]bool, len(commands))
	for _, command := range commands {
		result[strings.ToUpper(command)] = true
	}
	return result
}

func isReadCommand(command string) bool {
	switch command {
	case "GET", "EXISTS", "TTL", "PTTL", "MGET", "HGET", "SMEMBERS", "ZRANGE", "PING":
		return true
	default:
		return false
	}
}

func hasAllowedPrefix(key []byte, prefixes []string) bool {
	for _, prefix := range prefixes {
		if bytes.HasPrefix(key, []byte(prefix)) {
			return true
		}
	}
	return false
}

func cloneKeys(keys [][]byte) [][]byte {
	out := make([][]byte, len(keys))
	copy(out, keys)
	return out
}
