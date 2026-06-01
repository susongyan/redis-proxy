package com.zuomagai.redisproxy.dataplane.governance;

import com.zuomagai.redisproxy.dataplane.config.ProxyProperties;
import com.zuomagai.redisproxy.dataplane.protocol.ArgRef;
import com.zuomagai.redisproxy.dataplane.protocol.RespRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class GovernancePolicy {
    public static final String ALLOW = "allow";
    public static final String DENY = "deny";
    public static final String NOAUTH = "noauth";
    public static final String UNSUPPORTED = "unsupported";

    private GovernancePolicy() {
    }

    public static AuthResult authenticate(ProxyProperties.Governance governance, RespRequest request) {
        if (!governance.isEnabled()) {
            return new AuthResult(false, "", "-ERR AUTH disabled\r\n", "disabled");
        }
        if (request.argCount() != 3) {
            return new AuthResult(false, "", "-ERR AUTH requires namespace and token\r\n", "invalid");
        }
        String namespace = request.argUtf8(1);
        String token = request.argUtf8(2);
        ProxyProperties.Namespace candidate = governance.namespace(namespace);
        if (candidate != null) {
            if (candidate.getToken().equals(token)) {
                return new AuthResult(true, namespace, "+OK\r\n", "success");
            }
            return new AuthResult(false, namespace, "-ERR invalid namespace credentials\r\n", "invalid_token");
        }
        return new AuthResult(false, namespace, "-ERR invalid namespace credentials\r\n", "unknown_namespace");
    }

    public static Decision evaluate(ProxyProperties.Governance governance, String namespaceName, RespRequest request) {
        if (!governance.isEnabled()) {
            return new Decision(ALLOW, namespaceName, null, null, false, null);
        }
        String command = request.command();
        if ("AUTH".equals(command) || "QUIT".equals(command)) {
            return new Decision(ALLOW, namespaceName, null, null, false, null);
        }
        if (governance.isRequireAuth() && namespaceName.isBlank()) {
            return new Decision(NOAUTH, namespaceName, "-NOAUTH Authentication required\r\n", "unauthenticated", false, null);
        }
        ProxyProperties.Namespace namespace = namespace(governance, namespaceName);
        if (governance.isRequireAuth() && namespace == null) {
            return new Decision(NOAUTH, namespaceName, "-NOAUTH namespace disabled\r\n", "namespace_disabled", false, null);
        }
        if (contains(governance.getCommandPolicy().getDeniedCommands(), command)) {
            return new Decision(DENY, namespaceName, "-ERR command denied by proxy governance\r\n", "global_denied_command", false, null);
        }
        if (namespace != null && contains(namespace.getDeniedCommands(), command)) {
            return new Decision(DENY, namespaceName, "-ERR command denied by proxy governance\r\n", "namespace_denied_command", false, null);
        }
        boolean warn = contains(governance.getCommandPolicy().getWarnOnlyCommands(), command)
                || (namespace != null && contains(namespace.getWarnOnlyCommands(), command));
        if (warn) {
            return new Decision(ALLOW, namespaceName, null, null, true, "warn_only_command");
        }
        if (namespace != null && namespace.isReadOnly() && !readCommands().contains(command)) {
            return new Decision(DENY, namespaceName, "-ERR command denied by proxy governance\r\n", "readonly", warn, warn ? "warn_only_command" : null);
        }
        if (namespace != null && !namespace.getAllowedKeyPrefixes().isEmpty()) {
            KeyResult keys = keys(request);
            if (!keys.supported()) {
                return new Decision(UNSUPPORTED, namespaceName, "-ERR command key policy unsupported\r\n", "key_policy_unsupported", warn, warn ? "warn_only_command" : null);
            }
            for (ArgRef key : keys.keys()) {
                if (!hasAllowedPrefix(key, namespace.getAllowedKeyPrefixes())) {
                    return new Decision(DENY, namespaceName, "-ERR key denied by proxy governance\r\n", "key_prefix", warn, warn ? "warn_only_command" : null);
                }
            }
        }
        return new Decision(ALLOW, namespaceName, null, null, warn, warn ? "warn_only_command" : null);
    }

    public static Map<String, Object> summary(ProxyProperties.Governance governance) {
        List<Map<String, Object>> namespaces = governance.getNamespaces().stream()
                .map(namespace -> Map.<String, Object>of(
                        "name", namespace.getName(),
                        "readOnly", namespace.isReadOnly(),
                        "allowedKeyPrefixes", namespace.getAllowedKeyPrefixes(),
                        "deniedCommands", namespace.getDeniedCommands(),
                        "warnOnlyCommands", namespace.getWarnOnlyCommands(),
                        "limits", Map.of(
                                "maxConnections", namespace.getLimits().getMaxConnections(),
                                "maxQps", namespace.getLimits().getMaxQps(),
                                "maxInflight", namespace.getLimits().getMaxInflight()),
                        "disabledKeys", namespace.getDisabledKeys(),
                        "keyRules", namespace.getKeyRules()))
                .toList();
        return Map.of(
                "enabled", governance.isEnabled(),
                "requireAuth", governance.isRequireAuth(),
                "keyLimitWindowMillis", governance.getKeyLimitWindowMillis(),
                "keyLimitBucketMillis", governance.getKeyLimitBucketMillis(),
                "commandPolicy", Map.of(
                        "deniedCommands", governance.getCommandPolicy().getDeniedCommands(),
                        "warnOnlyCommands", governance.getCommandPolicy().getWarnOnlyCommands()),
                "namespaces", namespaces);
    }

    public static KeyResult keys(RespRequest request) {
        if (request.argCount() < 2) {
            return new KeyResult(List.of(), false);
        }
        return switch (request.command()) {
            case "GET", "SET", "EXPIRE", "PEXPIRE", "TTL", "PTTL", "HGET", "HSET", "HDEL", "LPUSH", "RPUSH", "LPOP", "RPOP", "SADD", "SREM", "SMEMBERS", "ZADD", "ZREM", "ZRANGE" ->
                    new KeyResult(List.of(request.arg(1)), true);
            case "DEL", "EXISTS", "MGET" -> new KeyResult(List.copyOf(request.args().subList(1, request.argCount())), true);
            case "MSET" -> msetKeys(request);
            default -> new KeyResult(List.of(), false);
        };
    }

    private static KeyResult msetKeys(RespRequest request) {
        if ((request.argCount() - 1) % 2 != 0) {
            return new KeyResult(List.of(), false);
        }
        List<ArgRef> keys = new ArrayList<>();
        for (int i = 1; i < request.argCount(); i += 2) {
            keys.add(request.arg(i));
        }
        return new KeyResult(keys, true);
    }

    private static ProxyProperties.Namespace namespace(ProxyProperties.Governance governance, String name) {
        return namespaceConfig(governance, name);
    }

    public static ProxyProperties.Namespace namespaceConfig(ProxyProperties.Governance governance, String name) {
        return governance.namespace(name);
    }

    private static boolean contains(List<String> commands, String command) {
        return commands.stream().map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toSet()).contains(command);
    }

    private static boolean hasAllowedPrefix(ArgRef key, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (key.startsWithUtf8(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> readCommands() {
        return Set.of("GET", "EXISTS", "TTL", "PTTL", "MGET", "HGET", "SMEMBERS", "ZRANGE", "PING");
    }

    public record AuthResult(boolean allowed, String namespace, String response, String result) {}
    public record Decision(String action, String namespace, String response, String reason, boolean warn, String warnReason) {}
    public record KeyResult(List<ArgRef> keys, boolean supported) {}
}
