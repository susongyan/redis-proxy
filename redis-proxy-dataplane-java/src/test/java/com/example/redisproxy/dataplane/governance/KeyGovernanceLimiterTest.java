package com.example.redisproxy.dataplane.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyGovernanceLimiterTest {
    @Test
    void rejectsDisabledExactKey() {
        KeyGovernanceLimiter limiter = new KeyGovernanceLimiter(new SimpleMeterRegistry());

        KeyGovernanceLimiter.Decision decision = limiter.evaluate(governance(), namespace(), request("GET", "app-a:blocked"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("exact_key_disabled");
    }

    @Test
    void rejectsDisabledRule() {
        KeyGovernanceLimiter limiter = new KeyGovernanceLimiter(new SimpleMeterRegistry());

        KeyGovernanceLimiter.Decision decision = limiter.evaluate(governance(), namespace(), request("GET", "app-a:disabled:1"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("rule_disabled");
        assertThat(decision.rule()).isEqualTo("disabled-prefix");
    }

    @Test
    void limitsWithSlidingWindowAndRecoversAfterWindow() {
        KeyGovernanceLimiter limiter = new KeyGovernanceLimiter(new SimpleMeterRegistry());
        limiter.setClock(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
        ProxyProperties.Governance governance = governance();
        ProxyProperties.Namespace namespace = namespace();

        assertThat(limiter.evaluate(governance, namespace, request("GET", "app-a:hot:1")).allowed()).isTrue();
        assertThat(limiter.evaluate(governance, namespace, request("GET", "app-a:hot:2")).allowed()).isFalse();

        limiter.setClock(Clock.fixed(Instant.ofEpochMilli(2_100), ZoneOffset.UTC));
        assertThat(limiter.evaluate(governance, namespace, request("GET", "app-a:hot:3")).allowed()).isTrue();
    }

    @Test
    void rejectsWholeMultiKeyCommand() {
        KeyGovernanceLimiter limiter = new KeyGovernanceLimiter(new SimpleMeterRegistry());

        KeyGovernanceLimiter.Decision decision = limiter.evaluate(governance(), namespace(), request("MGET", "app-a:1", "app-a:blocked"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("exact_key_disabled");
    }

    @Test
    void failsClosedWhenKeysAreUnsupported() {
        KeyGovernanceLimiter limiter = new KeyGovernanceLimiter(new SimpleMeterRegistry());

        KeyGovernanceLimiter.Decision decision = limiter.evaluate(governance(), namespace(), request("SCAN", "0"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("key_policy_unsupported");
    }

    @Test
    void recordsDecisionAndLimitMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KeyGovernanceLimiter limiter = new KeyGovernanceLimiter(registry);
        limiter.setClock(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
        ProxyProperties.Governance governance = governance();
        ProxyProperties.Namespace namespace = namespace();

        assertThat(limiter.evaluate(governance, namespace, request("GET", "app-a:hot:1")).allowed()).isTrue();
        assertThat(registry.get("redis.proxy.key.limit.config").tag("namespace", "app-a").tag("rule", "hot").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("redis.proxy.key.limit.window.usage").tag("namespace", "app-a").tag("rule", "hot").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("redis.proxy.key.governance.decisions").tag("namespace", "app-a").tag("rule", "hot").tag("command", "GET").tag("result", "allow").tag("reason", "").counter().count()).isEqualTo(1.0);

        assertThat(limiter.evaluate(governance, namespace, request("GET", "app-a:hot:2")).allowed()).isFalse();
        assertThat(registry.get("redis.proxy.key.governance.decisions").tag("namespace", "app-a").tag("rule", "hot").tag("command", "GET").tag("result", "reject").tag("reason", "qps_limit").counter().count()).isEqualTo(1.0);
    }

    private static ProxyProperties.Governance governance() {
        ProxyProperties.Governance governance = new ProxyProperties.Governance();
        governance.setEnabled(true);
        governance.setKeyLimitWindowMillis(1000);
        governance.setKeyLimitBucketMillis(100);
        return governance;
    }

    private static ProxyProperties.Namespace namespace() {
        ProxyProperties.Namespace namespace = new ProxyProperties.Namespace();
        namespace.setName("app-a");
        namespace.setDisabledKeys(List.of("app-a:blocked"));

        ProxyProperties.KeyRule disabled = new ProxyProperties.KeyRule();
        disabled.setName("disabled-prefix");
        disabled.setKeyPrefix("app-a:disabled:");
        disabled.setDisabled(true);

        ProxyProperties.KeyRule hot = new ProxyProperties.KeyRule();
        hot.setName("hot");
        hot.setKeyPrefix("app-a:hot:");
        hot.setMaxQps(1);

        namespace.setKeyRules(List.of(disabled, hot));
        return namespace;
    }

    private static RespRequest request(String... args) {
        return new RespRequest(
                Unpooled.EMPTY_BUFFER,
                Arrays.stream(args).map(arg -> arg.getBytes(StandardCharsets.US_ASCII)).toList());
    }
}
