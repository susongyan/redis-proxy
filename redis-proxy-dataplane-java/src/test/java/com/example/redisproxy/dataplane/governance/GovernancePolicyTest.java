package com.example.redisproxy.dataplane.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GovernancePolicyTest {
    @Test
    void authenticatesNamespaceToken() {
        GovernancePolicy.AuthResult result = GovernancePolicy.authenticate(config(), request("AUTH", "app-a", "token-a"));
        assertThat(result.allowed()).isTrue();
        assertThat(result.namespace()).isEqualTo("app-a");
        assertThat(result.result()).isEqualTo("success");

        result = GovernancePolicy.authenticate(config(), request("AUTH", "app-a", "bad"));
        assertThat(result.allowed()).isFalse();
        assertThat(result.result()).isEqualTo("invalid_token");
    }

    @Test
    void requiresAuthentication() {
        GovernancePolicy.Decision decision = GovernancePolicy.evaluate(config(), "", request("GET", "app-a:1"));
        assertThat(decision.action()).isEqualTo(GovernancePolicy.NOAUTH);
        assertThat(decision.reason()).isEqualTo("unauthenticated");
    }

    @Test
    void deniesAndWarnsCommands() {
        GovernancePolicy.Decision denied = GovernancePolicy.evaluate(config(), "app-a", request("FLUSHALL"));
        assertThat(denied.action()).isEqualTo(GovernancePolicy.DENY);
        assertThat(denied.reason()).isEqualTo("global_denied_command");

        GovernancePolicy.Decision warn = GovernancePolicy.evaluate(config(), "app-a", request("KEYS", "app-a:*"));
        assertThat(warn.action()).isEqualTo(GovernancePolicy.ALLOW);
        assertThat(warn.warn()).isTrue();
    }

    @Test
    void enforcesReadOnlyNamespace() {
        assertThat(GovernancePolicy.evaluate(config(), "reader", request("GET", "reader:1")).action()).isEqualTo(GovernancePolicy.ALLOW);
        GovernancePolicy.Decision denied = GovernancePolicy.evaluate(config(), "reader", request("SET", "reader:1", "v"));
        assertThat(denied.action()).isEqualTo(GovernancePolicy.DENY);
        assertThat(denied.reason()).isEqualTo("readonly");
    }

    @Test
    void enforcesAllowedKeyPrefixes() {
        assertThat(GovernancePolicy.evaluate(config(), "app-a", request("MSET", "app-a:1", "v1", "app-a:2", "v2")).action()).isEqualTo(GovernancePolicy.ALLOW);
        GovernancePolicy.Decision denied = GovernancePolicy.evaluate(config(), "app-a", request("GET", "other:1"));
        assertThat(denied.action()).isEqualTo(GovernancePolicy.DENY);
        assertThat(denied.reason()).isEqualTo("key_prefix");
        assertThat(GovernancePolicy.evaluate(config(), "app-a", request("SCAN", "0")).action()).isEqualTo(GovernancePolicy.UNSUPPORTED);
    }

    private static ProxyProperties.Governance config() {
        ProxyProperties.Governance governance = new ProxyProperties.Governance();
        governance.setEnabled(true);
        governance.setRequireAuth(true);
        ProxyProperties.CommandPolicy commandPolicy = new ProxyProperties.CommandPolicy();
        commandPolicy.setDeniedCommands(List.of("FLUSHALL", "FLUSHDB"));
        commandPolicy.setWarnOnlyCommands(List.of("KEYS"));
        governance.setCommandPolicy(commandPolicy);

        ProxyProperties.Namespace app = new ProxyProperties.Namespace();
        app.setName("app-a");
        app.setToken("token-a");
        app.setAllowedKeyPrefixes(List.of("app-a:"));
        ProxyProperties.Namespace reader = new ProxyProperties.Namespace();
        reader.setName("reader");
        reader.setToken("token-r");
        reader.setReadOnly(true);
        reader.setAllowedKeyPrefixes(List.of("reader:"));
        governance.setNamespaces(List.of(app, reader));
        return governance;
    }

    private static RespRequest request(String... args) {
        return new RespRequest(
                Unpooled.EMPTY_BUFFER,
                Arrays.stream(args).map(arg -> arg.getBytes(StandardCharsets.US_ASCII)).toList());
    }
}
