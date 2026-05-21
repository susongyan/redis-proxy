package com.example.redisproxy.dataplane.governance;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class KeyGovernanceLimiter {
    private final MeterRegistry registry;
    private final Map<String, SlidingWindow> windows = new HashMap<>();
    private final Map<String, AtomicInteger> limitConfigGauges = new HashMap<>();
    private final Map<String, AtomicInteger> limitWindowUsageGauges = new HashMap<>();
    private Clock clock = Clock.systemUTC();

    public KeyGovernanceLimiter(MeterRegistry registry) {
        this.registry = registry;
    }

    public synchronized Decision evaluate(ProxyProperties.Governance governance, ProxyProperties.Namespace namespace, RespRequest request) {
        if (!governance.isEnabled() || namespace == null || !hasKeyGovernance(namespace)) {
            return Decision.allow();
        }
        GovernancePolicy.KeyResult keys = GovernancePolicy.keys(request);
        if (!keys.supported()) {
            observeDecision(namespace.getName(), "unsupported", request.command(), "reject", "key_policy_unsupported");
            return Decision.rejected("unsupported", "key_policy_unsupported", "-ERR command key policy unsupported\r\n");
        }
        for (byte[] key : keys.keys()) {
            String text = new String(key, StandardCharsets.UTF_8);
            if (namespace.getDisabledKeys().contains(text)) {
                observeDecision(namespace.getName(), "exact", request.command(), "reject", "exact_key_disabled");
                return Decision.rejected("exact", "exact_key_disabled", "-ERR key disabled by proxy governance\r\n");
            }
            for (ProxyProperties.KeyRule rule : namespace.getKeyRules()) {
                if (!matches(rule, key)) {
                    continue;
                }
                observeKeyLimitConfig(namespace.getName(), rule);
                if (rule.isDisabled()) {
                    observeDecision(namespace.getName(), rule.getName(), request.command(), "reject", "rule_disabled");
                    return Decision.rejected(rule.getName(), "rule_disabled", "-ERR key disabled by proxy governance\r\n");
                }
                if (rule.getMaxQps() > 0) {
                    LimitResult limit = allow(governance, namespace.getName(), rule);
                    observeKeyLimitUsage(namespace.getName(), rule.getName(), limit.total());
                    if (!limit.allowed()) {
                        observeDecision(namespace.getName(), rule.getName(), request.command(), "reject", "qps_limit");
                        return Decision.rejected(rule.getName(), "qps_limit", "-ERR key limited by proxy governance\r\n");
                    }
                }
                observeDecision(namespace.getName(), rule.getName(), request.command(), "allow", "");
            }
        }
        return Decision.allow();
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private LimitResult allow(ProxyProperties.Governance governance, String namespace, ProxyProperties.KeyRule rule) {
        int bucketCount = governance.getKeyLimitWindowMillis() / governance.getKeyLimitBucketMillis();
        if (bucketCount <= 0) {
            bucketCount = 1;
        }
        long nowMillis = clock.millis();
        long currentBucket = nowMillis / governance.getKeyLimitBucketMillis();
        String key = namespace + "\u0000" + rule.getName();
        SlidingWindow window = windows.get(key);
        if (window == null || window.bucketMillis != governance.getKeyLimitBucketMillis() || window.buckets.length != bucketCount) {
            window = new SlidingWindow(governance.getKeyLimitBucketMillis(), bucketCount);
            windows.put(key, window);
        }
        int total = 0;
        for (Bucket bucket : window.buckets) {
            if (currentBucket - bucket.index < bucketCount) {
                total += bucket.count;
            }
        }
        if (total >= rule.getMaxQps()) {
            return new LimitResult(false, total);
        }
        int slot = (int) (currentBucket % bucketCount);
        if (window.buckets[slot].index != currentBucket) {
            window.buckets[slot] = new Bucket(currentBucket, 0);
        }
        window.buckets[slot].count++;
        return new LimitResult(true, total + 1);
    }

    private void observeDecision(String namespace, String rule, String command, String result, String reason) {
        registry.counter("redis.proxy.key.governance.decisions", "namespace", namespace, "rule", rule, "command", command, "result", result, "reason", reason).increment();
    }

    private void observeKeyLimitConfig(String namespace, ProxyProperties.KeyRule rule) {
        if (rule.getMaxQps() <= 0) {
            return;
        }
        String key = namespace + "\u0000" + rule.getName();
        limitConfigGauges.computeIfAbsent(key, ignored ->
                registry.gauge("redis.proxy.key.limit.config", List.of(Tag.of("namespace", namespace), Tag.of("rule", rule.getName())), new AtomicInteger()))
                .set(rule.getMaxQps());
    }

    private void observeKeyLimitUsage(String namespace, String rule, int total) {
        String key = namespace + "\u0000" + rule;
        limitWindowUsageGauges.computeIfAbsent(key, ignored ->
                registry.gauge("redis.proxy.key.limit.window.usage", List.of(Tag.of("namespace", namespace), Tag.of("rule", rule)), new AtomicInteger()))
                .set(total);
    }

    private static boolean hasKeyGovernance(ProxyProperties.Namespace namespace) {
        return !namespace.getDisabledKeys().isEmpty() || !namespace.getKeyRules().isEmpty();
    }

    private static boolean matches(ProxyProperties.KeyRule rule, byte[] key) {
        String text = new String(key, StandardCharsets.UTF_8);
        if (rule.getKeyPrefix() != null && !rule.getKeyPrefix().isBlank() && !text.startsWith(rule.getKeyPrefix())) {
            return false;
        }
        if (rule.getHashTag() != null && !rule.getHashTag().isBlank()) {
            return rule.getHashTag().equals(new String(hashTag(key), StandardCharsets.UTF_8));
        }
        return true;
    }

    private static byte[] hashTag(byte[] key) {
        int start = -1;
        for (int i = 0; i < key.length; i++) {
            if (key[i] == '{') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return key;
        }
        for (int i = start + 1; i < key.length; i++) {
            if (key[i] == '}') {
                if (i == start + 1) {
                    return key;
                }
                return java.util.Arrays.copyOfRange(key, start + 1, i);
            }
        }
        return key;
    }

    public record Decision(boolean allowed, String rule, String reason, String response) {
        static Decision allow() {
            return new Decision(true, null, null, null);
        }

        static Decision rejected(String rule, String reason, String response) {
            return new Decision(false, rule, reason, response);
        }
    }

    private record LimitResult(boolean allowed, int total) {
    }

    private static final class SlidingWindow {
        private final int bucketMillis;
        private final Bucket[] buckets;

        private SlidingWindow(int bucketMillis, int bucketCount) {
            this.bucketMillis = bucketMillis;
            this.buckets = new Bucket[bucketCount];
            for (int i = 0; i < bucketCount; i++) {
                this.buckets[i] = new Bucket(Long.MIN_VALUE, 0);
            }
        }
    }

    private static final class Bucket {
        private final long index;
        private int count;

        private Bucket(long index, int count) {
            this.index = index;
            this.count = count;
        }
    }
}
