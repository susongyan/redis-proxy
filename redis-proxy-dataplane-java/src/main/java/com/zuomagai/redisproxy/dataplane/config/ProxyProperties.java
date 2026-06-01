package com.zuomagai.redisproxy.dataplane.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "proxy")
public class ProxyProperties {
    private Instance instance = new Instance();
    private Server server = new Server();
    private Admin admin = new Admin();
    private String mode = "standalone";
    private Backends backends = new Backends();
    private Routing routing = new Routing();
    private Limits limits = new Limits();
    private ControlPlane controlPlane = new ControlPlane();
    private Analysis analysis = new Analysis();
    private Governance governance = new Governance();

    public void validate() {
        if (!"standalone".equals(mode) && !"cluster".equals(mode)) {
            throw new IllegalArgumentException("unsupported mode: " + mode);
        }
        if (instance.proxyId == null || instance.proxyId.isBlank()) {
            instance.proxyId = defaultProxyId();
        }
        if (routing.defaultCluster == null || routing.defaultCluster.isBlank()) {
            throw new IllegalArgumentException("routing.defaultCluster is required");
        }
        if (routing.clusterSlotsRefreshIntervalSeconds < 0) {
            throw new IllegalArgumentException("routing.clusterSlotsRefreshIntervalSeconds must be >= 0");
        }
        if (!List.of("client", "keySlot", "hashTag").contains(routing.backendAffinityStrategy)) {
            throw new IllegalArgumentException("routing.backendAffinityStrategy must be client, keySlot or hashTag");
        }
        if (routing.routeEpoch < 0) {
            throw new IllegalArgumentException("routing.routeEpoch must be >= 0");
        }
        if (limits.maxPipelineDepth <= 0 || limits.pipelineFlushBatchSize <= 0 || limits.pipelineFlushMaxDelayMillis < 0 || limits.maxRequestBytes <= 0 || limits.maxResponseBytes <= 0 || limits.largeResponseBytes < 0) {
            throw new IllegalArgumentException("limits must be positive, pipelineFlushMaxDelayMillis must be >= 0 and largeResponseBytes must be >= 0");
        }
        analysis.validate();
        if (controlPlane.enabled && (controlPlane.url == null || controlPlane.url.isBlank())) {
            throw new IllegalArgumentException("controlPlane.url is required when controlPlane.enabled=true");
        }
        if (controlPlane.pollIntervalSeconds < 0) {
            throw new IllegalArgumentException("controlPlane.pollIntervalSeconds must be >= 0");
        }
        if (controlPlane.watchTimeoutSeconds < 0) {
            throw new IllegalArgumentException("controlPlane.watchTimeoutSeconds must be >= 0");
        }
        if (controlPlane.requestTimeoutMillis < 0) {
            throw new IllegalArgumentException("controlPlane.requestTimeoutMillis must be >= 0");
        }
        governance.applyDefaults();
        governance.validate();
        Set<String> clusterNames = new HashSet<>();
        for (Cluster cluster : backends.clusters) {
            if (cluster.name == null || cluster.name.isBlank()) {
                throw new IllegalArgumentException("cluster name is required");
            }
            if (!clusterNames.add(cluster.name)) {
                throw new IllegalArgumentException("duplicate cluster: " + cluster.name);
            }
        }
        if (!clusterNames.contains(routing.defaultCluster)) {
            throw new IllegalArgumentException("default cluster not found: " + routing.defaultCluster);
        }
        Map<String, Boolean> namespaceNames = new HashMap<>();
        for (Namespace namespace : governance.namespaces) {
            namespaceNames.put(namespace.getName(), true);
        }
        for (RouteRule rule : routing.rules) {
            if (rule.name == null || rule.name.isBlank()) {
                throw new IllegalArgumentException("routing.rules.name is required");
            }
            if (!clusterNames.contains(rule.cluster)) {
                throw new IllegalArgumentException("routing rule " + rule.name + " references unknown cluster: " + rule.cluster);
            }
            if (rule.trafficPercent < 0 || rule.trafficPercent > 100) {
                throw new IllegalArgumentException("routing rule " + rule.name + " trafficPercent must be between 0 and 100");
            }
            if (rule.namespace != null && !rule.namespace.isBlank() && !namespaceNames.containsKey(rule.namespace)) {
                throw new IllegalArgumentException("routing rule " + rule.name + " references unknown namespace: " + rule.namespace);
            }
            if ((rule.namespace == null || rule.namespace.isBlank())
                    && (rule.keyPrefix == null || rule.keyPrefix.isBlank())
                    && (rule.keyPattern == null || rule.keyPattern.isBlank())
                    && (rule.hashTag == null || rule.hashTag.isBlank())) {
                throw new IllegalArgumentException("routing rule " + rule.name + " must set namespace, keyPrefix, keyPattern or hashTag");
            }
        }
    }

    public Instance getInstance() { return instance; }
    public void setInstance(Instance instance) { this.instance = instance == null ? new Instance() : instance; }
    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
    public Admin getAdmin() { return admin; }
    public void setAdmin(Admin admin) { this.admin = admin; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Backends getBackends() { return backends; }
    public void setBackends(Backends backends) { this.backends = backends; }
    public Routing getRouting() { return routing; }
    public void setRouting(Routing routing) { this.routing = routing; }
    public Limits getLimits() { return limits; }
    public void setLimits(Limits limits) { this.limits = limits; }
    public ControlPlane getControlPlane() { return controlPlane; }
    public void setControlPlane(ControlPlane controlPlane) { this.controlPlane = controlPlane; }
    public Analysis getAnalysis() { return analysis; }
    public void setAnalysis(Analysis analysis) { this.analysis = analysis; }
    public Governance getGovernance() { return governance; }
    public void setGovernance(Governance governance) { this.governance = governance; }

    private static String defaultProxyId() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            return host == null || host.isBlank() ? "proxy-java" : host;
        } catch (Exception e) {
            return "proxy-java";
        }
    }

    public static class Instance {
        private String proxyId = "";
        public String getProxyId() { return proxyId; }
        public void setProxyId(String proxyId) { this.proxyId = proxyId; }
    }

    public static class Server {
        private String listen = "0.0.0.0:6379";
        private int bossThreads = 1;
        private int workerThreads = 0;
        public String getListen() { return listen; }
        public void setListen(String listen) { this.listen = listen; }
        public int getBossThreads() { return bossThreads; }
        public void setBossThreads(int bossThreads) { this.bossThreads = bossThreads; }
        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    }

    public static class Admin {
        private String listen = "0.0.0.0:8080";
        public String getListen() { return listen; }
        public void setListen(String listen) { this.listen = listen; }
    }

    public static class Backends {
        private List<Cluster> clusters = new ArrayList<>();
        public List<Cluster> getClusters() { return clusters; }
        public void setClusters(List<Cluster> clusters) { this.clusters = clusters; }
    }

    public static class Cluster {
        private String name;
        private List<String> nodes = new ArrayList<>();
        private Pool pool = new Pool();
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<String> getNodes() { return nodes; }
        public void setNodes(List<String> nodes) { this.nodes = nodes; }
        public Pool getPool() { return pool; }
        public void setPool(Pool pool) { this.pool = pool; }
    }

    public static class Pool {
        private int connectionsPerNode = 16;
        private int maxInflightPerConnection = 1024;
        public int getConnectionsPerNode() { return connectionsPerNode; }
        public void setConnectionsPerNode(int connectionsPerNode) { this.connectionsPerNode = connectionsPerNode; }
        public int getMaxInflightPerConnection() { return maxInflightPerConnection; }
        public void setMaxInflightPerConnection(int maxInflightPerConnection) { this.maxInflightPerConnection = maxInflightPerConnection; }
    }

    public static class Routing {
        private String defaultCluster;
        private long routeEpoch = 1;
        private int clusterSlotsRefreshIntervalSeconds = 0;
        private String backendAffinityStrategy = "client";
        private List<RouteRule> rules = new ArrayList<>();
        public String getDefaultCluster() { return defaultCluster; }
        public void setDefaultCluster(String defaultCluster) { this.defaultCluster = defaultCluster; }
        public long getRouteEpoch() { return routeEpoch; }
        public void setRouteEpoch(long routeEpoch) { this.routeEpoch = routeEpoch; }
        public int getClusterSlotsRefreshIntervalSeconds() { return clusterSlotsRefreshIntervalSeconds; }
        public void setClusterSlotsRefreshIntervalSeconds(int clusterSlotsRefreshIntervalSeconds) { this.clusterSlotsRefreshIntervalSeconds = clusterSlotsRefreshIntervalSeconds; }
        public String getBackendAffinityStrategy() { return backendAffinityStrategy; }
        public void setBackendAffinityStrategy(String backendAffinityStrategy) { this.backendAffinityStrategy = backendAffinityStrategy == null || backendAffinityStrategy.isBlank() ? "client" : backendAffinityStrategy; }
        public List<RouteRule> getRules() { return rules; }
        public void setRules(List<RouteRule> rules) { this.rules = rules; }
    }

    public static class RouteRule {
        private String name;
        private String cluster;
        private String namespace;
        private String keyPrefix;
        private String keyPattern;
        private String hashTag;
        private int trafficPercent;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCluster() { return cluster; }
        public void setCluster(String cluster) { this.cluster = cluster; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public String getKeyPattern() { return keyPattern; }
        public void setKeyPattern(String keyPattern) { this.keyPattern = keyPattern; }
        public String getHashTag() { return hashTag; }
        public void setHashTag(String hashTag) { this.hashTag = hashTag; }
        public int getTrafficPercent() { return trafficPercent; }
        public void setTrafficPercent(int trafficPercent) { this.trafficPercent = trafficPercent; }
    }

    public static class ControlPlane {
        private boolean enabled;
        private String url;
        private int pollIntervalSeconds = 5;
        private int watchTimeoutSeconds = 30;
        private int requestTimeoutMillis = 1000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
        public int getWatchTimeoutSeconds() { return watchTimeoutSeconds; }
        public void setWatchTimeoutSeconds(int watchTimeoutSeconds) { this.watchTimeoutSeconds = watchTimeoutSeconds; }
        public int getRequestTimeoutMillis() { return requestTimeoutMillis; }
        public void setRequestTimeoutMillis(int requestTimeoutMillis) { this.requestTimeoutMillis = requestTimeoutMillis; }
    }

    public static class Limits {
        private int maxPipelineDepth = 1024;
        private int pipelineFlushBatchSize = 16;
        private int pipelineFlushMaxDelayMillis = 1;
        private int maxRequestBytes = 10 * 1024 * 1024;
        private int maxResponseBytes = 100 * 1024 * 1024;
        private int largeResponseBytes = 1024 * 1024;
        public int getMaxPipelineDepth() { return maxPipelineDepth; }
        public void setMaxPipelineDepth(int maxPipelineDepth) { this.maxPipelineDepth = maxPipelineDepth; }
        public int getPipelineFlushBatchSize() { return pipelineFlushBatchSize; }
        public void setPipelineFlushBatchSize(int pipelineFlushBatchSize) { this.pipelineFlushBatchSize = pipelineFlushBatchSize; }
        public int getPipelineFlushMaxDelayMillis() { return pipelineFlushMaxDelayMillis; }
        public void setPipelineFlushMaxDelayMillis(int pipelineFlushMaxDelayMillis) { this.pipelineFlushMaxDelayMillis = pipelineFlushMaxDelayMillis; }
        public int getMaxRequestBytes() { return maxRequestBytes; }
        public void setMaxRequestBytes(int maxRequestBytes) { this.maxRequestBytes = maxRequestBytes; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
        public int getLargeResponseBytes() { return largeResponseBytes; }
        public void setLargeResponseBytes(int largeResponseBytes) { this.largeResponseBytes = largeResponseBytes; }
    }

    public static class Analysis {
        private HotKey hotKey = new HotKey();
        private LargeKey largeKey = new LargeKey();
        private SlowQuery slowQuery = new SlowQuery();
        public HotKey getHotKey() { return hotKey; }
        public void setHotKey(HotKey hotKey) { this.hotKey = hotKey; }
        public LargeKey getLargeKey() { return largeKey; }
        public void setLargeKey(LargeKey largeKey) { this.largeKey = largeKey; }
        public SlowQuery getSlowQuery() { return slowQuery; }
        public void setSlowQuery(SlowQuery slowQuery) { this.slowQuery = slowQuery; }

        void validate() {
            hotKey.validate();
            largeKey.validate();
            slowQuery.validate();
        }
    }

    public static class HotKey {
        private boolean enabled = true;
        private int windowSeconds = 60;
        private int bucketMillis = 1000;
        private int maxTrackedKeys = 10_000;
        private int metricsTopN = 20;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
        public int getBucketMillis() { return bucketMillis; }
        public void setBucketMillis(int bucketMillis) { this.bucketMillis = bucketMillis; }
        public int getMaxTrackedKeys() { return maxTrackedKeys; }
        public void setMaxTrackedKeys(int maxTrackedKeys) { this.maxTrackedKeys = maxTrackedKeys; }
        public int getMetricsTopN() { return metricsTopN; }
        public void setMetricsTopN(int metricsTopN) { this.metricsTopN = metricsTopN; }

        void validate() {
            int windowMillis = windowSeconds * 1000;
            if (windowSeconds <= 0 || bucketMillis <= 0 || maxTrackedKeys <= 0 || metricsTopN <= 0 || windowMillis % bucketMillis != 0) {
                throw new IllegalArgumentException("analysis.hotKey windowSeconds, bucketMillis, maxTrackedKeys and metricsTopN must be positive and window must be divisible by bucket");
            }
        }
    }

    public static class LargeKey {
        private boolean enabled = true;
        private int requestBytesThreshold = 1024 * 1024;
        private int responseBytesThreshold = 1024 * 1024;
        private int windowSeconds = 300;
        private int bucketMillis = 1000;
        private int maxTrackedKeys = 10_000;
        private int debugTopN = 100;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRequestBytesThreshold() { return requestBytesThreshold; }
        public void setRequestBytesThreshold(int requestBytesThreshold) { this.requestBytesThreshold = requestBytesThreshold; }
        public int getResponseBytesThreshold() { return responseBytesThreshold; }
        public void setResponseBytesThreshold(int responseBytesThreshold) { this.responseBytesThreshold = responseBytesThreshold; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
        public int getBucketMillis() { return bucketMillis; }
        public void setBucketMillis(int bucketMillis) { this.bucketMillis = bucketMillis; }
        public int getMaxTrackedKeys() { return maxTrackedKeys; }
        public void setMaxTrackedKeys(int maxTrackedKeys) { this.maxTrackedKeys = maxTrackedKeys; }
        public int getDebugTopN() { return debugTopN; }
        public void setDebugTopN(int debugTopN) { this.debugTopN = debugTopN; }

        void validate() {
            int windowMillis = windowSeconds * 1000;
            if (requestBytesThreshold < 0 || responseBytesThreshold < 0 || windowSeconds <= 0 || bucketMillis <= 0 || maxTrackedKeys <= 0 || debugTopN <= 0 || windowMillis % bucketMillis != 0) {
                throw new IllegalArgumentException("analysis.largeKey thresholds must be non-negative and windowSeconds, bucketMillis, maxTrackedKeys and debugTopN must be positive with window divisible by bucket");
            }
        }
    }

    public static class SlowQuery {
        private boolean enabled = true;
        private int endToEndThresholdMillis = 100;
        private int backendThresholdMillis = 50;
        private int windowSeconds = 300;
        private int bucketMillis = 1000;
        private int maxTrackedKeys = 10_000;
        private int debugTopN = 100;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getEndToEndThresholdMillis() { return endToEndThresholdMillis; }
        public void setEndToEndThresholdMillis(int endToEndThresholdMillis) { this.endToEndThresholdMillis = endToEndThresholdMillis; }
        public int getBackendThresholdMillis() { return backendThresholdMillis; }
        public void setBackendThresholdMillis(int backendThresholdMillis) { this.backendThresholdMillis = backendThresholdMillis; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
        public int getBucketMillis() { return bucketMillis; }
        public void setBucketMillis(int bucketMillis) { this.bucketMillis = bucketMillis; }
        public int getMaxTrackedKeys() { return maxTrackedKeys; }
        public void setMaxTrackedKeys(int maxTrackedKeys) { this.maxTrackedKeys = maxTrackedKeys; }
        public int getDebugTopN() { return debugTopN; }
        public void setDebugTopN(int debugTopN) { this.debugTopN = debugTopN; }

        void validate() {
            int windowMillis = windowSeconds * 1000;
            if (endToEndThresholdMillis < 0 || backendThresholdMillis < 0 || windowSeconds <= 0 || bucketMillis <= 0 || maxTrackedKeys <= 0 || debugTopN <= 0 || windowMillis % bucketMillis != 0) {
                throw new IllegalArgumentException("analysis.slowQuery thresholds must be non-negative and windowSeconds, bucketMillis, maxTrackedKeys and debugTopN must be positive with window divisible by bucket");
            }
        }
    }

    public static class Governance {
        private boolean enabled;
        private boolean requireAuth;
        private int keyLimitWindowMillis = 1000;
        private int keyLimitBucketMillis = 100;
        private CommandPolicy commandPolicy = new CommandPolicy();
        private List<Namespace> namespaces = new ArrayList<>();
        private volatile Map<String, Namespace> namespacesByName = Map.of();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isRequireAuth() { return requireAuth; }
        public void setRequireAuth(boolean requireAuth) { this.requireAuth = requireAuth; }
        public int getKeyLimitWindowMillis() { return keyLimitWindowMillis; }
        public void setKeyLimitWindowMillis(int keyLimitWindowMillis) { this.keyLimitWindowMillis = keyLimitWindowMillis; }
        public int getKeyLimitBucketMillis() { return keyLimitBucketMillis; }
        public void setKeyLimitBucketMillis(int keyLimitBucketMillis) { this.keyLimitBucketMillis = keyLimitBucketMillis; }
        public CommandPolicy getCommandPolicy() { return commandPolicy; }
        public void setCommandPolicy(CommandPolicy commandPolicy) { this.commandPolicy = commandPolicy; }
        public List<Namespace> getNamespaces() { return namespaces; }
        public void setNamespaces(List<Namespace> namespaces) {
            this.namespaces = namespaces == null ? new ArrayList<>() : namespaces;
            rebuildNamespaceIndex();
        }

        public Namespace namespace(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            Namespace namespace = namespacesByName.get(name);
            if (namespace != null || namespacesByName.size() == namespaces.size()) {
                return namespace;
            }
            rebuildNamespaceIndex();
            return namespacesByName.get(name);
        }

        void applyDefaults() {
            if (enabled && !requireAuth) {
                requireAuth = true;
            }
            if (keyLimitWindowMillis == 0) {
                keyLimitWindowMillis = 1000;
            }
            if (keyLimitBucketMillis == 0) {
                keyLimitBucketMillis = 100;
            }
            if (commandPolicy.deniedCommands.isEmpty()) {
                commandPolicy.deniedCommands = new ArrayList<>(List.of("FLUSHALL", "FLUSHDB", "CONFIG", "SHUTDOWN", "DEBUG", "MODULE"));
            }
            if (commandPolicy.warnOnlyCommands.isEmpty()) {
                commandPolicy.warnOnlyCommands = new ArrayList<>(List.of("KEYS", "EVAL", "SCRIPT"));
            }
            commandPolicy.normalize();
            for (Namespace namespace : namespaces) {
                namespace.normalize();
            }
            rebuildNamespaceIndex();
        }

        void validate() {
            if (keyLimitWindowMillis <= 0 || keyLimitBucketMillis <= 0 || keyLimitWindowMillis % keyLimitBucketMillis != 0) {
                throw new IllegalArgumentException("governance key limit window must be positive and divisible by bucket");
            }
            Set<String> seen = new HashSet<>();
            commandPolicy.validate("governance.commandPolicy");
            for (Namespace namespace : namespaces) {
                if (namespace.name == null || namespace.name.isBlank()) {
                    throw new IllegalArgumentException("governance.namespaces.name is required");
                }
                if (namespace.token == null || namespace.token.isBlank()) {
                    throw new IllegalArgumentException("governance namespace " + namespace.name + " token is required");
                }
                if (!seen.add(namespace.name)) {
                    throw new IllegalArgumentException("duplicate governance namespace: " + namespace.name);
                }
                namespace.validate();
            }
            rebuildNamespaceIndex();
        }

        private void rebuildNamespaceIndex() {
            Map<String, Namespace> next = new LinkedHashMap<>();
            for (Namespace namespace : namespaces) {
                if (namespace.getName() != null && !namespace.getName().isBlank()) {
                    next.put(namespace.getName(), namespace);
                }
            }
            namespacesByName = Map.copyOf(next);
        }
    }

    public static class CommandPolicy {
        private List<String> deniedCommands = new ArrayList<>();
        private List<String> warnOnlyCommands = new ArrayList<>();
        public List<String> getDeniedCommands() { return deniedCommands; }
        public void setDeniedCommands(List<String> deniedCommands) { this.deniedCommands = deniedCommands; }
        public List<String> getWarnOnlyCommands() { return warnOnlyCommands; }
        public void setWarnOnlyCommands(List<String> warnOnlyCommands) { this.warnOnlyCommands = warnOnlyCommands; }

        void normalize() {
            deniedCommands = normalizeCommands(deniedCommands);
            warnOnlyCommands = normalizeCommands(warnOnlyCommands);
        }

        void validate(String field) {
            for (String command : deniedCommands) {
                validateCommand(field, command);
            }
            for (String command : warnOnlyCommands) {
                validateCommand(field, command);
            }
        }
    }

    public static class Namespace {
        private String name;
        private String token;
        private boolean readOnly;
        private List<String> allowedKeyPrefixes = new ArrayList<>();
        private List<String> deniedCommands = new ArrayList<>();
        private List<String> warnOnlyCommands = new ArrayList<>();
        private NamespaceLimits limits = new NamespaceLimits();
        private List<String> disabledKeys = new ArrayList<>();
        private List<KeyRule> keyRules = new ArrayList<>();
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public boolean isReadOnly() { return readOnly; }
        public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
        public List<String> getAllowedKeyPrefixes() { return allowedKeyPrefixes; }
        public void setAllowedKeyPrefixes(List<String> allowedKeyPrefixes) { this.allowedKeyPrefixes = allowedKeyPrefixes; }
        public List<String> getDeniedCommands() { return deniedCommands; }
        public void setDeniedCommands(List<String> deniedCommands) { this.deniedCommands = deniedCommands; }
        public List<String> getWarnOnlyCommands() { return warnOnlyCommands; }
        public void setWarnOnlyCommands(List<String> warnOnlyCommands) { this.warnOnlyCommands = warnOnlyCommands; }
        public NamespaceLimits getLimits() { return limits; }
        public void setLimits(NamespaceLimits limits) { this.limits = limits; }
        public List<String> getDisabledKeys() { return disabledKeys; }
        public void setDisabledKeys(List<String> disabledKeys) { this.disabledKeys = disabledKeys; }
        public List<KeyRule> getKeyRules() { return keyRules; }
        public void setKeyRules(List<KeyRule> keyRules) { this.keyRules = keyRules; }

        void normalize() {
            deniedCommands = normalizeCommands(deniedCommands);
            warnOnlyCommands = normalizeCommands(warnOnlyCommands);
        }

        void validate() {
            for (String command : deniedCommands) {
                validateCommand("governance.namespaces.deniedCommands", command);
            }
            for (String command : warnOnlyCommands) {
                validateCommand("governance.namespaces.warnOnlyCommands", command);
            }
            limits.validate(name);
            Set<String> ruleNames = new HashSet<>();
            for (KeyRule rule : keyRules) {
                rule.validate(name, ruleNames);
            }
        }
    }

    public static class NamespaceLimits {
        private int maxConnections;
        private int maxQps;
        private int maxInflight;
        public int getMaxConnections() { return maxConnections; }
        public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
        public int getMaxQps() { return maxQps; }
        public void setMaxQps(int maxQps) { this.maxQps = maxQps; }
        public int getMaxInflight() { return maxInflight; }
        public void setMaxInflight(int maxInflight) { this.maxInflight = maxInflight; }

        void validate(String namespace) {
            if (maxConnections < 0 || maxQps < 0 || maxInflight < 0) {
                throw new IllegalArgumentException("governance namespace " + namespace + " limits must be >= 0");
            }
        }
    }

    public static class KeyRule {
        private String name;
        private String keyPrefix;
        private String hashTag;
        private boolean disabled;
        private int maxQps;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public String getHashTag() { return hashTag; }
        public void setHashTag(String hashTag) { this.hashTag = hashTag; }
        public boolean isDisabled() { return disabled; }
        public void setDisabled(boolean disabled) { this.disabled = disabled; }
        public int getMaxQps() { return maxQps; }
        public void setMaxQps(int maxQps) { this.maxQps = maxQps; }

        void validate(String namespace, Set<String> seen) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("governance namespace " + namespace + " keyRules.name is required");
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("governance namespace " + namespace + " has duplicate key rule: " + name);
            }
            boolean hasKeyPrefix = keyPrefix != null && !keyPrefix.isBlank();
            boolean hasHashTag = hashTag != null && !hashTag.isBlank();
            if (!hasKeyPrefix && !hasHashTag) {
                throw new IllegalArgumentException("governance namespace " + namespace + " key rule " + name + " must set keyPrefix or hashTag");
            }
            if (maxQps < 0) {
                throw new IllegalArgumentException("governance namespace " + namespace + " key rule " + name + " maxQps must be >= 0");
            }
        }
    }

    private static List<String> normalizeCommands(List<String> commands) {
        List<String> normalized = new ArrayList<>();
        for (String command : commands) {
            normalized.add(command == null ? "" : command.trim().toUpperCase(Locale.ROOT));
        }
        return normalized;
    }

    private static void validateCommand(String field, String command) {
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
