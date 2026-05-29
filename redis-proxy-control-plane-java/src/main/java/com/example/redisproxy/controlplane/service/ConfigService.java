package com.example.redisproxy.controlplane.service;

import com.example.redisproxy.controlplane.model.ConfigDiff;
import com.example.redisproxy.controlplane.model.ConfigVersion;
import com.example.redisproxy.controlplane.model.ProxyConfig;
import com.example.redisproxy.controlplane.model.PublishRequest;
import com.example.redisproxy.controlplane.model.RollbackRequest;
import com.example.redisproxy.controlplane.model.RouteStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
        return new RouteStatus(
                version.versionId(),
                config.getRouting().getRouteEpoch(),
                version.versionId(),
                config.getRouting().getRouteEpoch(),
                RouteConfigHash.hash(config),
                config.getRouting().getDefaultCluster(),
                copyRules(config.getRouting().getRules()),
                config.getBackends().getClusters().stream().map(ProxyConfig.Cluster::getName).toList(),
                copyVersion(version));
    }

    public CompletableFuture<Optional<ProxyConfig>> watch(long routeEpoch, Duration timeout) {
        ProxyConfig config = current.get();
        if (config.getRouting().getRouteEpoch() > routeEpoch) {
            return CompletableFuture.completedFuture(Optional.of(copyConfig(config)));
        }
        CompletableFuture<Optional<ProxyConfig>> future = new CompletableFuture<>();
        Watcher watcher = new Watcher(routeEpoch, future);
        watchers.add(watcher);
        watcherTimeouts.schedule(() -> {
            if (future.complete(Optional.empty())) {
                watchers.remove(watcher);
            }
        }, Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);

        ProxyConfig latest = current.get();
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
            if (config.getRouting().getRouteEpoch() > watcher.routeEpoch()
                    && watcher.future().complete(Optional.of(copyConfig(config)))) {
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
            if (!hasNamespace && !hasKeyPrefix && !hasKeyPattern && !hasHashTag) {
                throw new IllegalArgumentException("routing rule " + rule.getName() + " must set namespace, keyPrefix, keyPattern or hashTag");
            }
        }
        validateGovernance(config.getGovernance());
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
                .map(rule -> rule.getName() + ":" + rule.getCluster() + ":" + rule.getNamespace() + ":" + rule.getKeyPrefix() + ":" + rule.getKeyPattern() + ":" + rule.getHashTag() + ":" + rule.getTrafficPercent())
                .toList()
                .toString();
    }

    private static String summarizeClusters(ProxyConfig config) {
        return config.getBackends().getClusters().stream()
                .map(cluster -> cluster.getName() + "=" + cluster.getNodes())
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

    private static ProxyConfig copyConfig(ProxyConfig source) {
        ProxyConfig copy = new ProxyConfig();
        copy.getServer().setListen(source.getServer().getListen());
        copy.getServer().setBossThreads(source.getServer().getBossThreads());
        copy.getServer().setWorkerThreads(source.getServer().getWorkerThreads());
        copy.getAdmin().setListen(source.getAdmin().getListen());
        copy.setMode(source.getMode());
        copy.getBackends().setClusters(source.getBackends().getClusters().stream().map(ConfigService::copyCluster).toList());
        copy.getRouting().setDefaultCluster(source.getRouting().getDefaultCluster());
        copy.getRouting().setRouteEpoch(source.getRouting().getRouteEpoch());
        copy.getRouting().setClusterSlotsRefreshIntervalSeconds(source.getRouting().getClusterSlotsRefreshIntervalSeconds());
        copy.getRouting().setBackendAffinityStrategy(source.getRouting().getBackendAffinityStrategy());
        copy.getRouting().setRules(copyRules(source.getRouting().getRules()));
        copy.getLimits().setMaxPipelineDepth(source.getLimits().getMaxPipelineDepth());
        copy.getLimits().setPipelineFlushBatchSize(source.getLimits().getPipelineFlushBatchSize());
        copy.getLimits().setPipelineFlushMaxDelayMillis(source.getLimits().getPipelineFlushMaxDelayMillis());
        copy.getLimits().setMaxRequestBytes(source.getLimits().getMaxRequestBytes());
        copy.getLimits().setMaxResponseBytes(source.getLimits().getMaxResponseBytes());
        copy.getLimits().setLargeResponseBytes(source.getLimits().getLargeResponseBytes());
        ProxyConfig.Analysis analysis = new ProxyConfig.Analysis();
        ProxyConfig.HotKey hotKey = new ProxyConfig.HotKey();
        hotKey.setEnabled(source.getAnalysis().getHotKey().isEnabled());
        hotKey.setWindowSeconds(source.getAnalysis().getHotKey().getWindowSeconds());
        hotKey.setBucketMillis(source.getAnalysis().getHotKey().getBucketMillis());
        hotKey.setMaxTrackedKeys(source.getAnalysis().getHotKey().getMaxTrackedKeys());
        hotKey.setMetricsTopN(source.getAnalysis().getHotKey().getMetricsTopN());
        analysis.setHotKey(hotKey);
        ProxyConfig.LargeKey largeKey = new ProxyConfig.LargeKey();
        largeKey.setEnabled(source.getAnalysis().getLargeKey().isEnabled());
        largeKey.setRequestBytesThreshold(source.getAnalysis().getLargeKey().getRequestBytesThreshold());
        largeKey.setResponseBytesThreshold(source.getAnalysis().getLargeKey().getResponseBytesThreshold());
        largeKey.setWindowSeconds(source.getAnalysis().getLargeKey().getWindowSeconds());
        largeKey.setBucketMillis(source.getAnalysis().getLargeKey().getBucketMillis());
        largeKey.setMaxTrackedKeys(source.getAnalysis().getLargeKey().getMaxTrackedKeys());
        largeKey.setDebugTopN(source.getAnalysis().getLargeKey().getDebugTopN());
        analysis.setLargeKey(largeKey);
        ProxyConfig.SlowQuery slowQuery = new ProxyConfig.SlowQuery();
        slowQuery.setEnabled(source.getAnalysis().getSlowQuery().isEnabled());
        slowQuery.setEndToEndThresholdMillis(source.getAnalysis().getSlowQuery().getEndToEndThresholdMillis());
        slowQuery.setBackendThresholdMillis(source.getAnalysis().getSlowQuery().getBackendThresholdMillis());
        slowQuery.setWindowSeconds(source.getAnalysis().getSlowQuery().getWindowSeconds());
        slowQuery.setBucketMillis(source.getAnalysis().getSlowQuery().getBucketMillis());
        slowQuery.setMaxTrackedKeys(source.getAnalysis().getSlowQuery().getMaxTrackedKeys());
        slowQuery.setDebugTopN(source.getAnalysis().getSlowQuery().getDebugTopN());
        analysis.setSlowQuery(slowQuery);
        copy.setAnalysis(analysis);
        copy.setGovernance(copyGovernance(source.getGovernance()));
        return copy;
    }

    private static ProxyConfig.Cluster copyCluster(ProxyConfig.Cluster source) {
        ProxyConfig.Cluster copy = new ProxyConfig.Cluster();
        copy.setName(source.getName());
        copy.setNodes(List.copyOf(source.getNodes()));
        copy.getPool().setConnectionsPerNode(source.getPool().getConnectionsPerNode());
        copy.getPool().setMaxInflightPerConnection(source.getPool().getMaxInflightPerConnection());
        return copy;
    }

    private static List<ProxyConfig.RouteRule> copyRules(List<ProxyConfig.RouteRule> rules) {
        return rules.stream().map(rule -> {
            ProxyConfig.RouteRule copy = new ProxyConfig.RouteRule();
            copy.setName(rule.getName());
            copy.setCluster(rule.getCluster());
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

    private static ProxyConfig defaultConfig() {
        ProxyConfig config = new ProxyConfig();
        ProxyConfig.Cluster cluster = new ProxyConfig.Cluster();
        cluster.setName("redis-a");
        cluster.setNodes(List.of("127.0.0.1:7000", "127.0.0.1:7001", "127.0.0.1:7002"));
        config.getBackends().setClusters(List.of(cluster));
        config.getRouting().setDefaultCluster("redis-a");
        return config;
    }

    private record Watcher(long routeEpoch, CompletableFuture<Optional<ProxyConfig>> future) {
    }
}
