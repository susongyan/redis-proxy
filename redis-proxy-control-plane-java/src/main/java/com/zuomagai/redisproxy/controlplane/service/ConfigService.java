package com.zuomagai.redisproxy.controlplane.service;

import com.zuomagai.redisproxy.controlplane.model.ConfigDiff;
import com.zuomagai.redisproxy.controlplane.model.ConfigVersion;
import com.zuomagai.redisproxy.controlplane.model.ProxyConfig;
import com.zuomagai.redisproxy.controlplane.model.PublishRequest;
import com.zuomagai.redisproxy.controlplane.model.RollbackRequest;
import com.zuomagai.redisproxy.controlplane.model.RouteStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ConfigService {
    private final ConfigRepository repository;
    private final AtomicReference<ProxyConfig> current = new AtomicReference<>();
    private final AtomicReference<ConfigVersion> currentVersion = new AtomicReference<>();
    private final CopyOnWriteArrayList<Watcher> watchers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService watcherTimeouts = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "config-watch-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public ConfigService() {
        this(new MemoryConfigRepository(new ObjectMapper()));
    }

    @Autowired
    public ConfigService(ConfigRepository repository) {
        this.repository = repository;
        ProxyConfig initial = defaultConfig();
        initial.getGovernance().applyDefaults();
        ConfigVersion version = repository.initializeIfEmpty(initial);
        current.set(copyConfig(version.config()));
        currentVersion.set(copyVersion(version));
    }

    public ProxyConfig get() {
        return copyConfig(current.get());
    }

    public ProxyConfig snapshotForGroup(String group) {
        return snapshotForGroup(current.get(), normalizeGroup(group));
    }

    public ProxyConfig update(ProxyConfig config) {
        config.getGovernance().applyDefaults();
        validateSemantics(config);
        ConfigVersion version = recordVersion(config, "system", "compat-put", "PUT", "APPROVED", null, true);
        return copyConfig(version.config());
    }

    public ConfigVersion publish(PublishRequest request) {
        ProxyConfig config = copyConfig(request.getConfig());
        config.getGovernance().applyDefaults();
        validateSemantics(config);
        long currentEpoch = current.get().getRouting().getRouteEpoch();
        if (config.getRouting().getRouteEpoch() <= currentEpoch) {
            throw new IllegalArgumentException("routing.routeEpoch must be greater than current routeEpoch " + currentEpoch);
        }
        return recordVersion(config, request.getOperator(), request.getReason(), "PUBLISH", approvalStatus(request.getApprovalStatus()), null, true);
    }

    public ConfigVersion rollback(RollbackRequest request) {
        ConfigVersion target = findVersion(request.getVersionId(), request.getRouteEpoch())
                .orElseThrow(() -> new IllegalArgumentException("rollback target version not found"));
        ProxyConfig rollback = copyConfig(target.config());
        rollback.getRouting().setRouteEpoch(current.get().getRouting().getRouteEpoch() + 1);
        rollback.getGovernance().applyDefaults();
        validateSemantics(rollback);
        return recordVersion(rollback, request.getOperator(), request.getReason(), "ROLLBACK", approvalStatus(request.getApprovalStatus()), target.versionId(), true);
    }

    public List<ConfigVersion> versions() {
        return repository.versions().stream().map(ConfigService::copyVersion).toList();
    }

    public ConfigVersion version(long versionId) {
        return repository.findByVersionId(versionId)
                .map(ConfigService::copyVersion)
                .orElseThrow(() -> new IllegalArgumentException("version not found: " + versionId));
    }

    public ConfigDiff diff(long fromVersionId, long toVersionId) {
        ConfigVersion from = version(fromVersionId);
        ConfigVersion to = version(toVersionId);
        List<String> changes = new ArrayList<>();
        ProxyConfig a = from.config();
        ProxyConfig b = to.config();
        addChange(changes, "mode", a.getMode(), b.getMode());
        addChange(changes, "routing.routeEpoch", a.getRouting().getRouteEpoch(), b.getRouting().getRouteEpoch());
        addChange(changes, "routing.defaultCluster", a.getRouting().getDefaultCluster(), b.getRouting().getDefaultCluster());
        addChange(changes, "routing.backendAffinityStrategy", a.getRouting().getBackendAffinityStrategy(), b.getRouting().getBackendAffinityStrategy());
        addChange(changes, "routing.rules", summarizeRules(a), summarizeRules(b));
        addChange(changes, "proxyGroups", summarizeProxyGroups(a), summarizeProxyGroups(b));
        addChange(changes, "backends.clusters", summarizeClusters(a), summarizeClusters(b));
        addChange(changes, "limits.maxPipelineDepth", a.getLimits().getMaxPipelineDepth(), b.getLimits().getMaxPipelineDepth());
        addChange(changes, "limits.pipelineFlushBatchSize", a.getLimits().getPipelineFlushBatchSize(), b.getLimits().getPipelineFlushBatchSize());
        addChange(changes, "limits.pipelineFlushMaxDelayMillis", a.getLimits().getPipelineFlushMaxDelayMillis(), b.getLimits().getPipelineFlushMaxDelayMillis());
        addChange(changes, "limits.maxRequestBytes", a.getLimits().getMaxRequestBytes(), b.getLimits().getMaxRequestBytes());
        addChange(changes, "limits.maxResponseBytes", a.getLimits().getMaxResponseBytes(), b.getLimits().getMaxResponseBytes());
        addChange(changes, "limits.largeResponseBytes", a.getLimits().getLargeResponseBytes(), b.getLimits().getLargeResponseBytes());
        addChange(changes, "analysis.hotKey", summarizeHotKey(a), summarizeHotKey(b));
        addChange(changes, "analysis.largeKey", summarizeLargeKey(a), summarizeLargeKey(b));
        addChange(changes, "analysis.slowQuery", summarizeSlowQuery(a), summarizeSlowQuery(b));
        addChange(changes, "governance", summarizeGovernance(a), summarizeGovernance(b));
        return new ConfigDiff(fromVersionId, toVersionId, changes);
    }

    public RouteStatus routeStatus() {
        ConfigVersion version = currentVersion.get();
        ProxyConfig config = version.config();
        List<RouteStatus.GroupRouteStatus> groups = groupStatuses(config, version.versionId());
        RouteStatus.GroupRouteStatus defaultStatus = groups.stream()
                .filter(group -> "default".equals(group.group()))
                .findFirst()
                .orElse(groups.getFirst());
        return new RouteStatus(
                version.versionId(),
                config.getRouting().getRouteEpoch(),
                defaultStatus.expectedVersionId(),
                defaultStatus.expectedRouteEpoch(),
                defaultStatus.expectedConfigHash(),
                defaultStatus.defaultCluster(),
                defaultStatus.rules(),
                defaultStatus.clusters(),
                groups,
                copyVersion(version));
    }

    public CompletableFuture<Optional<ProxyConfig>> watch(long routeEpoch, String group, Duration timeout) {
        String normalizedGroup = normalizeGroup(group);
        ProxyConfig config = snapshotForGroup(current.get(), normalizedGroup);
        if (config.getRouting().getRouteEpoch() > routeEpoch) {
            return CompletableFuture.completedFuture(Optional.of(copyConfig(config)));
        }
        CompletableFuture<Optional<ProxyConfig>> future = new CompletableFuture<>();
        Watcher watcher = new Watcher(routeEpoch, normalizedGroup, future);
        watchers.add(watcher);
        watcherTimeouts.schedule(() -> {
            if (future.complete(Optional.empty())) {
                watchers.remove(watcher);
            }
        }, Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);

        ProxyConfig latest = snapshotForGroup(current.get(), normalizedGroup);
        if (latest.getRouting().getRouteEpoch() > routeEpoch && future.complete(Optional.of(copyConfig(latest)))) {
            watchers.remove(watcher);
        }
        return future;
    }

    private ConfigVersion recordVersion(ProxyConfig config, String operator, String reason, String action, String approvalStatus, Long rollbackFromVersionId, boolean notifyWatchers) {
        ProxyConfig snapshot = copyConfig(config);
        ConfigVersion version = repository.saveAndActivate(
                snapshot,
                blankDefault(operator, "system"),
                blankDefault(reason, "unspecified"),
                action,
                approvalStatus,
                rollbackFromVersionId);
        current.set(copyConfig(snapshot));
        currentVersion.set(copyVersion(version));
        if (notifyWatchers) {
            completeMatchingWatchers(snapshot);
        }
        return copyVersion(version);
    }

    private void completeMatchingWatchers(ProxyConfig config) {
        for (Watcher watcher : watchers) {
            ProxyConfig scoped = snapshotForGroup(config, watcher.group());
            if (scoped.getRouting().getRouteEpoch() > watcher.routeEpoch()
                    && watcher.future().complete(Optional.of(copyConfig(scoped)))) {
                watchers.remove(watcher);
            }
        }
    }

    @PreDestroy
    public void stop() {
        watcherTimeouts.shutdownNow();
    }

    private static void validateSemantics(ProxyConfig config) {
        config.getGovernance().applyDefaults();
        List<String> clusterNames = config.getBackends().getClusters().stream()
                .map(ProxyConfig.Cluster::getName)
                .toList();
        if (!clusterNames.contains(config.getRouting().getDefaultCluster())) {
            throw new IllegalArgumentException("routing.defaultCluster does not exist in backends.clusters");
        }
        if (!List.of("standalone", "cluster").contains(config.getMode())) {
            throw new IllegalArgumentException("mode must be standalone or cluster");
        }
        for (ProxyConfig.Cluster cluster : config.getBackends().getClusters()) {
            if (cluster.getAuth().isEnabled()
                    && (cluster.getAuth().getPassword() == null || cluster.getAuth().getPassword().isBlank())) {
                throw new IllegalArgumentException("backend cluster " + cluster.getName() + " auth.password is required when auth.enabled=true");
            }
        }
        if (config.getRouting().getRouteEpoch() < 0) {
            throw new IllegalArgumentException("routing.routeEpoch must be >= 0");
        }
        if (config.getRouting().getClusterSlotsRefreshIntervalSeconds() < 0) {
            throw new IllegalArgumentException("routing.clusterSlotsRefreshIntervalSeconds must be >= 0");
        }
        if (!List.of("client", "keySlot", "hashTag").contains(config.getRouting().getBackendAffinityStrategy())) {
            throw new IllegalArgumentException("routing.backendAffinityStrategy must be client, keySlot or hashTag");
        }
        if (config.getLimits().getMaxPipelineDepth() <= 0
                || config.getLimits().getPipelineFlushBatchSize() <= 0
                || config.getLimits().getPipelineFlushMaxDelayMillis() < 0
                || config.getLimits().getMaxRequestBytes() <= 0
                || config.getLimits().getMaxResponseBytes() <= 0
                || config.getLimits().getLargeResponseBytes() < 0) {
            throw new IllegalArgumentException("limits must be positive, pipelineFlushMaxDelayMillis must be >= 0 and largeResponseBytes must be >= 0");
        }
        validateAnalysis(config.getAnalysis());
        List<String> namespaceNames = config.getGovernance().getNamespaces().stream()
                .map(ProxyConfig.Namespace::getName)
                .toList();
        for (ProxyConfig.RouteRule rule : config.getRouting().getRules()) {
            if (!clusterNames.contains(rule.getCluster())) {
                throw new IllegalArgumentException("routing rule " + rule.getName() + " references unknown cluster");
            }
            if (rule.getTrafficPercent() < 0 || rule.getTrafficPercent() > 100) {
                throw new IllegalArgumentException("routing rule " + rule.getName() + " trafficPercent must be between 0 and 100");
            }
            boolean hasNamespace = rule.getNamespace() != null && !rule.getNamespace().isBlank();
            boolean hasKeyPrefix = rule.getKeyPrefix() != null && !rule.getKeyPrefix().isBlank();
            boolean hasKeyPattern = rule.getKeyPattern() != null && !rule.getKeyPattern().isBlank();
            boolean hasHashTag = rule.getHashTag() != null && !rule.getHashTag().isBlank();
            if (hasNamespace && !namespaceNames.contains(rule.getNamespace())) {
                throw new IllegalArgumentException("routing rule " + rule.getName() + " references unknown namespace");
            }
            if (!rule.isMatchAll() && !hasNamespace && !hasKeyPrefix && !hasKeyPattern && !hasHashTag) {
                throw new IllegalArgumentException("routing rule " + rule.getName() + " must set matchAll, namespace, keyPrefix, keyPattern or hashTag");
            }
        }
        validateProxyGroups(config, clusterNames, namespaceNames);
        validateGovernance(config.getGovernance());
    }

    private static void validateProxyGroups(ProxyConfig config, List<String> clusterNames, List<String> namespaceNames) {
        Set<String> seenGroups = new HashSet<>();
        for (ProxyConfig.ProxyGroup group : config.getProxyGroups()) {
            String groupName = normalizeGroup(group.getName());
            if (!seenGroups.add(groupName)) {
                throw new IllegalArgumentException("proxy group is duplicated: " + groupName);
            }
            if (group.getEnabledClusters().isEmpty()) {
                throw new IllegalArgumentException("proxy group " + groupName + " enabledClusters must not be empty");
            }
            Set<String> enabled = new HashSet<>(group.getEnabledClusters());
            for (String clusterName : enabled) {
                if (!clusterNames.contains(clusterName)) {
                    throw new IllegalArgumentException("proxy group " + groupName + " references unknown cluster " + clusterName);
                }
            }
            ProxyConfig.Routing routing = group.getRouting() == null ? config.getRouting() : group.getRouting();
            if (!enabled.contains(routing.getDefaultCluster())) {
                throw new IllegalArgumentException("proxy group " + groupName + " defaultCluster must be in enabledClusters");
            }
            if (!List.of("client", "keySlot", "hashTag").contains(routing.getBackendAffinityStrategy())) {
                throw new IllegalArgumentException("proxy group " + groupName + " backendAffinityStrategy must be client, keySlot or hashTag");
            }
            for (ProxyConfig.RouteRule rule : routing.getRules()) {
                if (!enabled.contains(rule.getCluster())) {
                    throw new IllegalArgumentException("proxy group " + groupName + " routing rule " + rule.getName() + " cluster must be in enabledClusters");
                }
                if (rule.getTrafficPercent() < 0 || rule.getTrafficPercent() > 100) {
                    throw new IllegalArgumentException("proxy group " + groupName + " routing rule " + rule.getName() + " trafficPercent must be between 0 and 100");
                }
                boolean hasNamespace = rule.getNamespace() != null && !rule.getNamespace().isBlank();
                boolean hasKeyPrefix = rule.getKeyPrefix() != null && !rule.getKeyPrefix().isBlank();
                boolean hasKeyPattern = rule.getKeyPattern() != null && !rule.getKeyPattern().isBlank();
                boolean hasHashTag = rule.getHashTag() != null && !rule.getHashTag().isBlank();
                if (hasNamespace && !namespaceNames.contains(rule.getNamespace())) {
                    throw new IllegalArgumentException("proxy group " + groupName + " routing rule " + rule.getName() + " references unknown namespace");
                }
                if (!rule.isMatchAll() && !hasNamespace && !hasKeyPrefix && !hasKeyPattern && !hasHashTag) {
                    throw new IllegalArgumentException("proxy group " + groupName + " routing rule " + rule.getName() + " must set matchAll, namespace, keyPrefix, keyPattern or hashTag");
                }
            }
            if (group.getAnalysis() != null) {
                validateAnalysis(group.getAnalysis());
            }
            if (group.getLimits() != null) {
                validateLimits(group.getLimits());
            }
            if (group.getGovernance() != null) {
                group.getGovernance().applyDefaults();
                validateGovernance(group.getGovernance());
            }
        }
    }

    private static void validateLimits(ProxyConfig.Limits limits) {
        if (limits.getMaxPipelineDepth() <= 0
                || limits.getPipelineFlushBatchSize() <= 0
                || limits.getPipelineFlushMaxDelayMillis() < 0
                || limits.getMaxRequestBytes() <= 0
                || limits.getMaxResponseBytes() <= 0
                || limits.getLargeResponseBytes() < 0) {
            throw new IllegalArgumentException("proxy group limits must be positive, pipelineFlushMaxDelayMillis must be >= 0 and largeResponseBytes must be >= 0");
        }
    }

    private static void validateAnalysis(ProxyConfig.Analysis analysis) {
        ProxyConfig.HotKey hotKey = analysis.getHotKey();
        int windowMillis = hotKey.getWindowSeconds() * 1000;
        if (hotKey.getWindowSeconds() <= 0
                || hotKey.getBucketMillis() <= 0
                || hotKey.getMaxTrackedKeys() <= 0
                || hotKey.getMetricsTopN() <= 0
                || windowMillis % hotKey.getBucketMillis() != 0) {
            throw new IllegalArgumentException("analysis.hotKey windowSeconds, bucketMillis, maxTrackedKeys and metricsTopN must be positive and window must be divisible by bucket");
        }
        ProxyConfig.LargeKey largeKey = analysis.getLargeKey();
        int largeWindowMillis = largeKey.getWindowSeconds() * 1000;
        if (largeKey.getRequestBytesThreshold() < 0
                || largeKey.getResponseBytesThreshold() < 0
                || largeKey.getWindowSeconds() <= 0
                || largeKey.getBucketMillis() <= 0
                || largeKey.getMaxTrackedKeys() <= 0
                || largeKey.getDebugTopN() <= 0
                || largeWindowMillis % largeKey.getBucketMillis() != 0) {
            throw new IllegalArgumentException("analysis.largeKey thresholds must be non-negative and windowSeconds, bucketMillis, maxTrackedKeys and debugTopN must be positive with window divisible by bucket");
        }
        ProxyConfig.SlowQuery slowQuery = analysis.getSlowQuery();
        int slowWindowMillis = slowQuery.getWindowSeconds() * 1000;
        if (slowQuery.getEndToEndThresholdMillis() < 0
                || slowQuery.getBackendThresholdMillis() < 0
                || slowQuery.getWindowSeconds() <= 0
                || slowQuery.getBucketMillis() <= 0
                || slowQuery.getMaxTrackedKeys() <= 0
                || slowQuery.getDebugTopN() <= 0
                || slowWindowMillis % slowQuery.getBucketMillis() != 0) {
            throw new IllegalArgumentException("analysis.slowQuery thresholds must be non-negative and windowSeconds, bucketMillis, maxTrackedKeys and debugTopN must be positive with window divisible by bucket");
        }
    }

    private static void validateGovernance(ProxyConfig.Governance governance) {
        if (governance.getKeyLimitWindowMillis() <= 0
                || governance.getKeyLimitBucketMillis() <= 0
                || governance.getKeyLimitWindowMillis() % governance.getKeyLimitBucketMillis() != 0) {
            throw new IllegalArgumentException("governance key limit window must be positive and divisible by bucket");
        }
        validateCommands("governance.commandPolicy.deniedCommands", governance.getCommandPolicy().getDeniedCommands());
        validateCommands("governance.commandPolicy.warnOnlyCommands", governance.getCommandPolicy().getWarnOnlyCommands());
        List<String> seen = new ArrayList<>();
        for (ProxyConfig.Namespace namespace : governance.getNamespaces()) {
            if (namespace.getName() == null || namespace.getName().isBlank()) {
                throw new IllegalArgumentException("governance.namespaces.name is required");
            }
            if (namespace.getToken() == null || namespace.getToken().isBlank()) {
                throw new IllegalArgumentException("governance namespace " + namespace.getName() + " token is required");
            }
            if (seen.contains(namespace.getName())) {
                throw new IllegalArgumentException("duplicate governance namespace: " + namespace.getName());
            }
            seen.add(namespace.getName());
            validateCommands("governance.namespaces.deniedCommands", namespace.getDeniedCommands());
            validateCommands("governance.namespaces.warnOnlyCommands", namespace.getWarnOnlyCommands());
            validateNamespaceLimits(namespace);
            validateKeyRules(namespace);
        }
    }

    private static void validateNamespaceLimits(ProxyConfig.Namespace namespace) {
        if (namespace.getLimits().getMaxConnections() < 0
                || namespace.getLimits().getMaxQps() < 0
                || namespace.getLimits().getMaxInflight() < 0) {
            throw new IllegalArgumentException("governance namespace " + namespace.getName() + " limits must be >= 0");
        }
    }

    private static void validateKeyRules(ProxyConfig.Namespace namespace) {
        List<String> seen = new ArrayList<>();
        for (ProxyConfig.KeyRule rule : namespace.getKeyRules()) {
            if (rule.getName() == null || rule.getName().isBlank()) {
                throw new IllegalArgumentException("governance namespace " + namespace.getName() + " keyRules.name is required");
            }
            if (seen.contains(rule.getName())) {
                throw new IllegalArgumentException("governance namespace " + namespace.getName() + " has duplicate key rule: " + rule.getName());
            }
            seen.add(rule.getName());
            boolean hasKeyPrefix = rule.getKeyPrefix() != null && !rule.getKeyPrefix().isBlank();
            boolean hasHashTag = rule.getHashTag() != null && !rule.getHashTag().isBlank();
            if (!hasKeyPrefix && !hasHashTag) {
                throw new IllegalArgumentException("governance namespace " + namespace.getName() + " key rule " + rule.getName() + " must set keyPrefix or hashTag");
            }
            if (rule.getMaxQps() < 0) {
                throw new IllegalArgumentException("governance namespace " + namespace.getName() + " key rule " + rule.getName() + " maxQps must be >= 0");
            }
        }
    }

    private static void validateCommands(String field, List<String> commands) {
        for (String command : commands) {
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException(field + " has invalid command");
            }
            for (int i = 0; i < command.length(); i++) {
                char c = command.charAt(i);
                if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                    throw new IllegalArgumentException(field + " has invalid command: " + command);
                }
            }
        }
    }

    private Optional<ConfigVersion> findVersion(Long versionId, Long routeEpoch) {
        if (versionId != null) {
            return repository.findByVersionId(versionId);
        }
        if (routeEpoch != null) {
            return repository.findByRouteEpoch(routeEpoch);
        }
        throw new IllegalArgumentException("versionId or routeEpoch is required");
    }

    private static void addChange(List<String> changes, String field, Object from, Object to) {
        if (!String.valueOf(from).equals(String.valueOf(to))) {
            changes.add(field + ": " + from + " -> " + to);
        }
    }

    private static String summarizeRules(ProxyConfig config) {
        return config.getRouting().getRules().stream()
                .map(rule -> rule.getName() + ":" + rule.getCluster() + ":" + rule.isMatchAll() + ":" + rule.getNamespace() + ":" + rule.getKeyPrefix() + ":" + rule.getKeyPattern() + ":" + rule.getHashTag() + ":" + rule.getTrafficPercent())
                .toList()
                .toString();
    }

    private static String summarizeClusters(ProxyConfig config) {
        return config.getBackends().getClusters().stream()
                .map(cluster -> cluster.getName()
                        + "=" + cluster.getNodes()
                        + ":auth=" + cluster.getAuth().isEnabled()
                        + ":" + cluster.getAuth().getUsername()
                        + ":" + cluster.getAuth().getPassword())
                .toList()
                .toString();
    }

    private static String summarizeProxyGroups(ProxyConfig config) {
        return config.getProxyGroups().stream()
                .map(group -> normalizeGroup(group.getName())
                        + ":enabled=" + group.getEnabledClusters()
                        + ":default=" + (group.getRouting() == null ? "" : group.getRouting().getDefaultCluster())
                        + ":rules=" + (group.getRouting() == null ? List.of() : group.getRouting().getRules().stream()
                        .map(rule -> rule.getName() + ":" + rule.getCluster() + ":" + rule.getNamespace() + ":" + rule.getKeyPrefix() + ":" + rule.getHashTag() + ":" + rule.getTrafficPercent())
                        .toList()))
                .toList()
                .toString();
    }

    private static String summarizeHotKey(ProxyConfig config) {
        ProxyConfig.HotKey hotKey = config.getAnalysis().getHotKey();
        return hotKey.isEnabled()
                + ":" + hotKey.getWindowSeconds()
                + ":" + hotKey.getBucketMillis()
                + ":" + hotKey.getMaxTrackedKeys()
                + ":" + hotKey.getMetricsTopN();
    }

    private static String summarizeLargeKey(ProxyConfig config) {
        ProxyConfig.LargeKey largeKey = config.getAnalysis().getLargeKey();
        return largeKey.isEnabled()
                + ":" + largeKey.getRequestBytesThreshold()
                + ":" + largeKey.getResponseBytesThreshold()
                + ":" + largeKey.getWindowSeconds()
                + ":" + largeKey.getBucketMillis()
                + ":" + largeKey.getMaxTrackedKeys()
                + ":" + largeKey.getDebugTopN();
    }

    private static String summarizeSlowQuery(ProxyConfig config) {
        ProxyConfig.SlowQuery slowQuery = config.getAnalysis().getSlowQuery();
        return slowQuery.isEnabled()
                + ":" + slowQuery.getEndToEndThresholdMillis()
                + ":" + slowQuery.getBackendThresholdMillis()
                + ":" + slowQuery.getWindowSeconds()
                + ":" + slowQuery.getBucketMillis()
                + ":" + slowQuery.getMaxTrackedKeys()
                + ":" + slowQuery.getDebugTopN();
    }

    private static String summarizeGovernance(ProxyConfig config) {
        return config.getGovernance().isEnabled()
                + ":" + config.getGovernance().isRequireAuth()
                + ":" + config.getGovernance().getKeyLimitWindowMillis()
                + ":" + config.getGovernance().getKeyLimitBucketMillis()
                + ":" + config.getGovernance().getCommandPolicy().getDeniedCommands()
                + ":" + config.getGovernance().getCommandPolicy().getWarnOnlyCommands()
                + ":" + config.getGovernance().getNamespaces().stream()
                .map(namespace -> namespace.getName()
                        + ":" + namespace.isReadOnly()
                        + ":" + namespace.getAllowedKeyPrefixes()
                        + ":" + namespace.getDeniedCommands()
                        + ":" + namespace.getWarnOnlyCommands()
                        + ":" + namespace.getLimits().getMaxConnections()
                        + ":" + namespace.getLimits().getMaxQps()
                        + ":" + namespace.getLimits().getMaxInflight()
                        + ":" + namespace.getDisabledKeys()
                        + ":" + namespace.getKeyRules().stream()
                        .map(rule -> rule.getName() + ":" + rule.getKeyPrefix() + ":" + rule.getHashTag() + ":" + rule.isDisabled() + ":" + rule.getMaxQps())
                        .toList())
                .toList();
    }

    private static String approvalStatus(String value) {
        return blankDefault(value, "APPROVED");
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static ConfigVersion copyVersion(ConfigVersion version) {
        return new ConfigVersion(
                version.versionId(),
                version.publishedAt(),
                version.operator(),
                version.reason(),
                version.action(),
                version.approvalStatus(),
                version.rollbackFromVersionId(),
                version.routeEpoch(),
                copyConfig(version.config()));
    }

    private static List<RouteStatus.GroupRouteStatus> groupStatuses(ProxyConfig config, long versionId) {
        List<String> groups = config.getProxyGroups().isEmpty()
                ? List.of("default")
                : config.getProxyGroups().stream().map(group -> normalizeGroup(group.getName())).toList();
        return groups.stream().map(groupName -> {
            ProxyConfig snapshot = snapshotForGroup(config, groupName);
            return new RouteStatus.GroupRouteStatus(
                    groupName,
                    versionId,
                    snapshot.getRouting().getRouteEpoch(),
                    RouteConfigHash.hash(snapshot),
                    snapshot.getRouting().getDefaultCluster(),
                    snapshot.getBackends().getClusters().stream().map(ProxyConfig.Cluster::getName).toList(),
                    copyRules(snapshot.getRouting().getRules()));
        }).toList();
    }

    private static ProxyConfig snapshotForGroup(ProxyConfig source, String groupName) {
        ProxyConfig snapshot = copyConfig(source);
        ProxyConfig.ProxyGroup group = findProxyGroup(source, groupName);
        if (group == null) {
            snapshot.setProxyGroups(List.of());
            return snapshot;
        }
        Set<String> enabledClusters = new HashSet<>(group.getEnabledClusters());
        snapshot.getBackends().setClusters(source.getBackends().getClusters().stream()
                .filter(cluster -> enabledClusters.contains(cluster.getName()))
                .map(ConfigService::copyCluster)
                .toList());
        ProxyConfig.Routing routing = group.getRouting() == null ? copyRouting(source.getRouting()) : copyRouting(group.getRouting());
        routing.setRouteEpoch(source.getRouting().getRouteEpoch());
        snapshot.setRouting(routing);
        if (group.getLimits() != null) {
            snapshot.setLimits(copyLimits(group.getLimits()));
        }
        if (group.getAnalysis() != null) {
            snapshot.setAnalysis(copyAnalysis(group.getAnalysis()));
        }
        if (group.getGovernance() != null) {
            snapshot.setGovernance(copyGovernance(group.getGovernance()));
            snapshot.getGovernance().applyDefaults();
        }
        snapshot.setProxyGroups(List.of());
        return snapshot;
    }

    private static ProxyConfig.ProxyGroup findProxyGroup(ProxyConfig config, String groupName) {
        if (config.getProxyGroups().isEmpty()) {
            return null;
        }
        String normalized = normalizeGroup(groupName);
        return config.getProxyGroups().stream()
                .filter(group -> normalized.equals(normalizeGroup(group.getName())))
                .findFirst()
                .or(() -> config.getProxyGroups().stream().filter(group -> "default".equals(normalizeGroup(group.getName()))).findFirst())
                .orElse(null);
    }

    private static String normalizeGroup(String group) {
        return group == null || group.isBlank() ? "default" : group.trim();
    }

    private static ProxyConfig copyConfig(ProxyConfig source) {
        ProxyConfig copy = new ProxyConfig();
        copy.getServer().setListen(source.getServer().getListen());
        copy.getServer().setBossThreads(source.getServer().getBossThreads());
        copy.getServer().setWorkerThreads(source.getServer().getWorkerThreads());
        copy.getAdmin().setListen(source.getAdmin().getListen());
        copy.setMode(source.getMode());
        copy.getBackends().setClusters(source.getBackends().getClusters().stream().map(ConfigService::copyCluster).toList());
        copy.setRouting(copyRouting(source.getRouting()));
        copy.setLimits(copyLimits(source.getLimits()));
        copy.setAnalysis(copyAnalysis(source.getAnalysis()));
        copy.setGovernance(copyGovernance(source.getGovernance()));
        copy.setProxyGroups(source.getProxyGroups().stream().map(ConfigService::copyProxyGroup).toList());
        return copy;
    }

    private static ProxyConfig.Routing copyRouting(ProxyConfig.Routing source) {
        ProxyConfig.Routing copy = new ProxyConfig.Routing();
        copy.setDefaultCluster(source.getDefaultCluster());
        copy.setRouteEpoch(source.getRouteEpoch());
        copy.setClusterSlotsRefreshIntervalSeconds(source.getClusterSlotsRefreshIntervalSeconds());
        copy.setBackendAffinityStrategy(source.getBackendAffinityStrategy());
        copy.setRules(copyRules(source.getRules()));
        return copy;
    }

    private static ProxyConfig.Limits copyLimits(ProxyConfig.Limits source) {
        ProxyConfig.Limits copy = new ProxyConfig.Limits();
        copy.setMaxPipelineDepth(source.getMaxPipelineDepth());
        copy.setPipelineFlushBatchSize(source.getPipelineFlushBatchSize());
        copy.setPipelineFlushMaxDelayMillis(source.getPipelineFlushMaxDelayMillis());
        copy.setMaxRequestBytes(source.getMaxRequestBytes());
        copy.setMaxResponseBytes(source.getMaxResponseBytes());
        copy.setLargeResponseBytes(source.getLargeResponseBytes());
        return copy;
    }

    private static ProxyConfig.Analysis copyAnalysis(ProxyConfig.Analysis source) {
        ProxyConfig.Analysis analysis = new ProxyConfig.Analysis();
        ProxyConfig.HotKey hotKey = new ProxyConfig.HotKey();
        hotKey.setEnabled(source.getHotKey().isEnabled());
        hotKey.setWindowSeconds(source.getHotKey().getWindowSeconds());
        hotKey.setBucketMillis(source.getHotKey().getBucketMillis());
        hotKey.setMaxTrackedKeys(source.getHotKey().getMaxTrackedKeys());
        hotKey.setMetricsTopN(source.getHotKey().getMetricsTopN());
        analysis.setHotKey(hotKey);
        ProxyConfig.LargeKey largeKey = new ProxyConfig.LargeKey();
        largeKey.setEnabled(source.getLargeKey().isEnabled());
        largeKey.setRequestBytesThreshold(source.getLargeKey().getRequestBytesThreshold());
        largeKey.setResponseBytesThreshold(source.getLargeKey().getResponseBytesThreshold());
        largeKey.setWindowSeconds(source.getLargeKey().getWindowSeconds());
        largeKey.setBucketMillis(source.getLargeKey().getBucketMillis());
        largeKey.setMaxTrackedKeys(source.getLargeKey().getMaxTrackedKeys());
        largeKey.setDebugTopN(source.getLargeKey().getDebugTopN());
        analysis.setLargeKey(largeKey);
        ProxyConfig.SlowQuery slowQuery = new ProxyConfig.SlowQuery();
        slowQuery.setEnabled(source.getSlowQuery().isEnabled());
        slowQuery.setEndToEndThresholdMillis(source.getSlowQuery().getEndToEndThresholdMillis());
        slowQuery.setBackendThresholdMillis(source.getSlowQuery().getBackendThresholdMillis());
        slowQuery.setWindowSeconds(source.getSlowQuery().getWindowSeconds());
        slowQuery.setBucketMillis(source.getSlowQuery().getBucketMillis());
        slowQuery.setMaxTrackedKeys(source.getSlowQuery().getMaxTrackedKeys());
        slowQuery.setDebugTopN(source.getSlowQuery().getDebugTopN());
        analysis.setSlowQuery(slowQuery);
        return analysis;
    }

    private static ProxyConfig.Cluster copyCluster(ProxyConfig.Cluster source) {
        ProxyConfig.Cluster copy = new ProxyConfig.Cluster();
        copy.setName(source.getName());
        copy.setNodes(List.copyOf(source.getNodes()));
        ProxyConfig.Auth auth = new ProxyConfig.Auth();
        auth.setEnabled(source.getAuth().isEnabled());
        auth.setUsername(source.getAuth().getUsername());
        auth.setPassword(source.getAuth().getPassword());
        copy.setAuth(auth);
        copy.getPool().setConnectionsPerNode(source.getPool().getConnectionsPerNode());
        copy.getPool().setMaxInflightPerConnection(source.getPool().getMaxInflightPerConnection());
        return copy;
    }

    private static List<ProxyConfig.RouteRule> copyRules(List<ProxyConfig.RouteRule> rules) {
        if (rules == null) {
            return List.of();
        }
        return rules.stream().map(rule -> {
            ProxyConfig.RouteRule copy = new ProxyConfig.RouteRule();
            copy.setName(rule.getName());
            copy.setCluster(rule.getCluster());
            copy.setMatchAll(rule.isMatchAll());
            copy.setNamespace(rule.getNamespace());
            copy.setKeyPrefix(rule.getKeyPrefix());
            copy.setKeyPattern(rule.getKeyPattern());
            copy.setHashTag(rule.getHashTag());
            copy.setTrafficPercent(rule.getTrafficPercent());
            return copy;
        }).toList();
    }

    private static ProxyConfig.Governance copyGovernance(ProxyConfig.Governance source) {
        ProxyConfig.Governance copy = new ProxyConfig.Governance();
        copy.setEnabled(source.isEnabled());
        copy.setRequireAuth(source.isRequireAuth());
        copy.setKeyLimitWindowMillis(source.getKeyLimitWindowMillis());
        copy.setKeyLimitBucketMillis(source.getKeyLimitBucketMillis());
        ProxyConfig.CommandPolicy commandPolicy = new ProxyConfig.CommandPolicy();
        commandPolicy.setDeniedCommands(List.copyOf(source.getCommandPolicy().getDeniedCommands()));
        commandPolicy.setWarnOnlyCommands(List.copyOf(source.getCommandPolicy().getWarnOnlyCommands()));
        copy.setCommandPolicy(commandPolicy);
        copy.setNamespaces(source.getNamespaces().stream().map(ConfigService::copyNamespace).toList());
        return copy;
    }

    private static ProxyConfig.Namespace copyNamespace(ProxyConfig.Namespace source) {
        ProxyConfig.Namespace copy = new ProxyConfig.Namespace();
        copy.setName(source.getName());
        copy.setToken(source.getToken());
        copy.setReadOnly(source.isReadOnly());
        copy.setAllowedKeyPrefixes(List.copyOf(source.getAllowedKeyPrefixes()));
        copy.setDeniedCommands(List.copyOf(source.getDeniedCommands()));
        copy.setWarnOnlyCommands(List.copyOf(source.getWarnOnlyCommands()));
        ProxyConfig.NamespaceLimits limits = new ProxyConfig.NamespaceLimits();
        limits.setMaxConnections(source.getLimits().getMaxConnections());
        limits.setMaxQps(source.getLimits().getMaxQps());
        limits.setMaxInflight(source.getLimits().getMaxInflight());
        copy.setLimits(limits);
        copy.setDisabledKeys(List.copyOf(source.getDisabledKeys()));
        copy.setKeyRules(source.getKeyRules().stream().map(ConfigService::copyKeyRule).toList());
        return copy;
    }

    private static ProxyConfig.KeyRule copyKeyRule(ProxyConfig.KeyRule source) {
        ProxyConfig.KeyRule copy = new ProxyConfig.KeyRule();
        copy.setName(source.getName());
        copy.setKeyPrefix(source.getKeyPrefix());
        copy.setHashTag(source.getHashTag());
        copy.setDisabled(source.isDisabled());
        copy.setMaxQps(source.getMaxQps());
        return copy;
    }

    private static ProxyConfig.ProxyGroup copyProxyGroup(ProxyConfig.ProxyGroup source) {
        ProxyConfig.ProxyGroup copy = new ProxyConfig.ProxyGroup();
        copy.setName(source.getName());
        copy.setEnabledClusters(List.copyOf(source.getEnabledClusters()));
        if (source.getRouting() != null) {
            copy.setRouting(copyRouting(source.getRouting()));
        }
        if (source.getLimits() != null) {
            copy.setLimits(copyLimits(source.getLimits()));
        }
        if (source.getAnalysis() != null) {
            copy.setAnalysis(copyAnalysis(source.getAnalysis()));
        }
        if (source.getGovernance() != null) {
            copy.setGovernance(copyGovernance(source.getGovernance()));
        }
        return copy;
    }

    private static ProxyConfig defaultConfig() {
        ProxyConfig config = new ProxyConfig();
        ProxyConfig.Cluster cluster = new ProxyConfig.Cluster();
        cluster.setName("redis-a");
        cluster.setNodes(List.of("127.0.0.1:7000", "127.0.0.1:7001", "127.0.0.1:7002"));
        config.getBackends().setClusters(List.of(cluster));
        config.getRouting().setDefaultCluster("redis-a");
        return config;
    }

    private record Watcher(long routeEpoch, String group, CompletableFuture<Optional<ProxyConfig>> future) {
    }
}
