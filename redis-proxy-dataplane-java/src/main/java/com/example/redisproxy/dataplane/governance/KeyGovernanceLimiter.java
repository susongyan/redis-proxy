package com.example.redisproxy.dataplane.governance;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KeyGovernanceLimiter {
    private final Map<String, SlidingWindow> windows = new HashMap<>();
    private Clock clock = Clock.systemUTC();

    public synchronized Decision evaluate(ProxyProperties.Governance governance, ProxyProperties.Namespace namespace, RespRequest request) {
        if (!governance.isEnabled() || namespace == null || !hasKeyGovernance(namespace)) {
            return Decision.allow();
        }
        GovernancePolicy.KeyResult keys = GovernancePolicy.keys(request);
        if (!keys.supported()) {
            return Decision.rejected("unsupported", "key_policy_unsupported", "-ERR command key policy unsupported\r\n");
        }
        for (byte[] key : keys.keys()) {
            String text = new String(key, StandardCharsets.UTF_8);
            if (namespace.getDisabledKeys().contains(text)) {
                return Decision.rejected("exact", "exact_key_disabled", "-ERR key disabled by proxy governance\r\n");
            }
            for (ProxyProperties.KeyRule rule : namespace.getKeyRules()) {
                if (!matches(rule, key)) {
                    continue;
                }
                if (rule.isDisabled()) {
                    return Decision.rejected(rule.getName(), "rule_disabled", "-ERR key disabled by proxy governance\r\n");
                }
                if (rule.getMaxQps() > 0 && !allow(governance, namespace.getName(), rule)) {
                    return Decision.rejected(rule.getName(), "qps_limit", "-ERR key limited by proxy governance\r\n");
                }
            }
        }
        return Decision.allow();
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private boolean allow(ProxyProperties.Governance governance, String namespace, ProxyProperties.KeyRule rule) {
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
            return false;
        }
        int slot = (int) (currentBucket % bucketCount);
        if (window.buckets[slot].index != currentBucket) {
            window.buckets[slot] = new Bucket(currentBucket, 0);
        }
        window.buckets[slot].count++;
        return true;
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
