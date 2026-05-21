package proxy

import (
	"bytes"
	"sync"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/governance"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
)

type keyGovernanceLimiter struct {
	mu      sync.Mutex
	reg     *metrics.Registry
	windows map[string]*slidingWindow
	now     func() time.Time
}

type keyGovernanceDecision struct {
	Allowed  bool
	Response []byte
	Rule     string
	Reason   string
}

type slidingWindow struct {
	bucketMillis int64
	buckets      []windowBucket
}

type windowBucket struct {
	index int64
	count int
}

func newKeyGovernanceLimiter(reg *metrics.Registry) *keyGovernanceLimiter {
	return &keyGovernanceLimiter{
		reg:     reg,
		windows: map[string]*slidingWindow{},
		now:     time.Now,
	}
}

func (l *keyGovernanceLimiter) evaluate(cfg config.GovernanceConfig, namespace config.NamespaceConfig, req protocol.Request) keyGovernanceDecision {
	if !cfg.Enabled || !governance.HasKeyGovernance(namespace) {
		return keyGovernanceDecision{Allowed: true}
	}
	keys, supported := governance.Keys(req)
	if !supported {
		l.observeDecision(namespace.Name, "unsupported", req.Command(), "reject", "key_policy_unsupported")
		return keyGovernanceDecision{Response: []byte("-ERR command key policy unsupported\r\n"), Rule: "unsupported", Reason: "key_policy_unsupported"}
	}
	disabled := stringSet(namespace.DisabledKeys)
	for _, key := range keys {
		if disabled[string(key)] {
			l.observeDecision(namespace.Name, "exact", req.Command(), "reject", "exact_key_disabled")
			return keyGovernanceDecision{Response: []byte("-ERR key disabled by proxy governance\r\n"), Rule: "exact", Reason: "exact_key_disabled"}
		}
		for _, rule := range namespace.KeyRules {
			if !matchesRule(rule, key) {
				continue
			}
			l.observeKeyLimitConfig(namespace.Name, rule)
			if rule.Disabled {
				l.observeDecision(namespace.Name, rule.Name, req.Command(), "reject", "rule_disabled")
				return keyGovernanceDecision{Response: []byte("-ERR key disabled by proxy governance\r\n"), Rule: rule.Name, Reason: "rule_disabled"}
			}
			if rule.MaxQPS > 0 {
				allowed, total := l.allow(cfg, namespace.Name, rule)
				l.observeKeyLimitUsage(namespace.Name, rule.Name, total)
				if !allowed {
					l.observeDecision(namespace.Name, rule.Name, req.Command(), "reject", "qps_limit")
					return keyGovernanceDecision{Response: []byte("-ERR key limited by proxy governance\r\n"), Rule: rule.Name, Reason: "qps_limit"}
				}
			}
			l.observeDecision(namespace.Name, rule.Name, req.Command(), "allow", "")
		}
	}
	return keyGovernanceDecision{Allowed: true}
}

func (l *keyGovernanceLimiter) allow(cfg config.GovernanceConfig, namespace string, rule config.KeyRuleConfig) (bool, int) {
	windowMillis := int64(cfg.KeyLimitWindowMillis)
	bucketMillis := int64(cfg.KeyLimitBucketMillis)
	bucketCount := int(windowMillis / bucketMillis)
	if bucketCount <= 0 {
		bucketCount = 1
	}
	nowMillis := l.now().UnixMilli()
	currentBucket := nowMillis / bucketMillis
	key := namespace + "\x00" + rule.Name

	l.mu.Lock()
	defer l.mu.Unlock()
	window := l.windows[key]
	if window == nil || window.bucketMillis != bucketMillis || len(window.buckets) != bucketCount {
		window = &slidingWindow{bucketMillis: bucketMillis, buckets: make([]windowBucket, bucketCount)}
		l.windows[key] = window
	}
	total := 0
	for _, bucket := range window.buckets {
		if currentBucket-bucket.index < int64(bucketCount) {
			total += bucket.count
		}
	}
	if total >= rule.MaxQPS {
		return false, total
	}
	slot := currentBucket % int64(bucketCount)
	if window.buckets[slot].index != currentBucket {
		window.buckets[slot] = windowBucket{index: currentBucket}
	}
	window.buckets[slot].count++
	return true, total + 1
}

func (l *keyGovernanceLimiter) observeDecision(namespace, rule, command, result, reason string) {
	if l.reg == nil {
		return
	}
	l.reg.KeyGovernanceDecisions.WithLabelValues(namespace, rule, command, result, reason).Inc()
}

func (l *keyGovernanceLimiter) observeKeyLimitConfig(namespace string, rule config.KeyRuleConfig) {
	if l.reg == nil || rule.MaxQPS <= 0 {
		return
	}
	l.reg.KeyLimitConfig.WithLabelValues(namespace, rule.Name).Set(float64(rule.MaxQPS))
}

func (l *keyGovernanceLimiter) observeKeyLimitUsage(namespace, rule string, total int) {
	if l.reg == nil {
		return
	}
	l.reg.KeyLimitWindowUsage.WithLabelValues(namespace, rule).Set(float64(total))
}

func matchesRule(rule config.KeyRuleConfig, key []byte) bool {
	if rule.KeyPrefix != "" && !bytes.HasPrefix(key, []byte(rule.KeyPrefix)) {
		return false
	}
	if rule.HashTag != "" && string(hashTag(key)) != rule.HashTag {
		return false
	}
	return true
}

func stringSet(values []string) map[string]bool {
	result := make(map[string]bool, len(values))
	for _, value := range values {
		result[value] = true
	}
	return result
}

func hashTag(key []byte) []byte {
	start := -1
	for i, b := range key {
		if b == '{' {
			start = i
			break
		}
	}
	if start < 0 {
		return key
	}
	for i := start + 1; i < len(key); i++ {
		if key[i] == '}' {
			if i == start+1 {
				return key
			}
			return key[start+1 : i]
		}
	}
	return key
}
