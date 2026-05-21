package com.example.redisproxy.controlplane.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ProxyConfig {
    @Valid private Server server = new Server();
    @Valid private Admin admin = new Admin();
    @NotBlank private String mode = "cluster";
    @Valid private Backends backends = new Backends();
    @Valid private Routing routing = new Routing();
    @Valid private Limits limits = new Limits();
    @Valid private Governance governance = new Governance();

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
    public Governance getGovernance() { return governance; }
    public void setGovernance(Governance governance) { this.governance = governance; }

    public static class Server {
        @NotBlank private String listen = "0.0.0.0:6379";
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
        @NotBlank private String listen = "0.0.0.0:8080";
        public String getListen() { return listen; }
        public void setListen(String listen) { this.listen = listen; }
    }

    public static class Backends {
        @Valid @NotEmpty private List<Cluster> clusters = new ArrayList<>();
        public List<Cluster> getClusters() { return clusters; }
        public void setClusters(List<Cluster> clusters) { this.clusters = clusters; }
    }

    public static class Cluster {
        @NotBlank private String name;
        @NotEmpty private List<String> nodes = new ArrayList<>();
        @Valid private Pool pool = new Pool();
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<String> getNodes() { return nodes; }
        public void setNodes(List<String> nodes) { this.nodes = nodes; }
        public Pool getPool() { return pool; }
        public void setPool(Pool pool) { this.pool = pool; }
    }

    public static class Pool {
        @Positive private int connectionsPerNode = 16;
        @Positive private int maxInflightPerConnection = 1024;
        public int getConnectionsPerNode() { return connectionsPerNode; }
        public void setConnectionsPerNode(int connectionsPerNode) { this.connectionsPerNode = connectionsPerNode; }
        public int getMaxInflightPerConnection() { return maxInflightPerConnection; }
        public void setMaxInflightPerConnection(int maxInflightPerConnection) { this.maxInflightPerConnection = maxInflightPerConnection; }
    }

    public static class Routing {
        @NotBlank private String defaultCluster = "redis-a";
        private long routeEpoch = 1;
        private int clusterSlotsRefreshIntervalSeconds = 0;
        @Valid private List<RouteRule> rules = new ArrayList<>();
        public String getDefaultCluster() { return defaultCluster; }
        public void setDefaultCluster(String defaultCluster) { this.defaultCluster = defaultCluster; }
        public long getRouteEpoch() { return routeEpoch; }
        public void setRouteEpoch(long routeEpoch) { this.routeEpoch = routeEpoch; }
        public int getClusterSlotsRefreshIntervalSeconds() { return clusterSlotsRefreshIntervalSeconds; }
        public void setClusterSlotsRefreshIntervalSeconds(int clusterSlotsRefreshIntervalSeconds) { this.clusterSlotsRefreshIntervalSeconds = clusterSlotsRefreshIntervalSeconds; }
        public List<RouteRule> getRules() { return rules; }
        public void setRules(List<RouteRule> rules) { this.rules = rules; }
    }

    public static class RouteRule {
        @NotBlank private String name;
        @NotBlank private String cluster;
        private String keyPrefix;
        private String hashTag;
        private int trafficPercent;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCluster() { return cluster; }
        public void setCluster(String cluster) { this.cluster = cluster; }
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public String getHashTag() { return hashTag; }
        public void setHashTag(String hashTag) { this.hashTag = hashTag; }
        public int getTrafficPercent() { return trafficPercent; }
        public void setTrafficPercent(int trafficPercent) { this.trafficPercent = trafficPercent; }
    }

    public static class Limits {
        @Positive private int maxPipelineDepth = 1024;
        @Positive private int maxRequestBytes = 10 * 1024 * 1024;
        @Positive private int maxResponseBytes = 100 * 1024 * 1024;
        private int largeResponseBytes = 1024 * 1024;
        public int getMaxPipelineDepth() { return maxPipelineDepth; }
        public void setMaxPipelineDepth(int maxPipelineDepth) { this.maxPipelineDepth = maxPipelineDepth; }
        public int getMaxRequestBytes() { return maxRequestBytes; }
        public void setMaxRequestBytes(int maxRequestBytes) { this.maxRequestBytes = maxRequestBytes; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
        public int getLargeResponseBytes() { return largeResponseBytes; }
        public void setLargeResponseBytes(int largeResponseBytes) { this.largeResponseBytes = largeResponseBytes; }
    }

    public static class Governance {
        private boolean enabled;
        private boolean requireAuth;
        private int keyLimitWindowMillis = 1000;
        private int keyLimitBucketMillis = 100;
        @Valid private CommandPolicy commandPolicy = new CommandPolicy();
        @Valid private List<Namespace> namespaces = new ArrayList<>();
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
        public void setNamespaces(List<Namespace> namespaces) { this.namespaces = namespaces; }

        public void applyDefaults() {
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
    }

    public static class Namespace {
        private String name;
        private String token;
        private boolean readOnly;
        private List<String> allowedKeyPrefixes = new ArrayList<>();
        private List<String> deniedCommands = new ArrayList<>();
        private List<String> warnOnlyCommands = new ArrayList<>();
        @Valid private NamespaceLimits limits = new NamespaceLimits();
        private List<String> disabledKeys = new ArrayList<>();
        @Valid private List<KeyRule> keyRules = new ArrayList<>();
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
    }

    private static List<String> normalizeCommands(List<String> commands) {
        List<String> normalized = new ArrayList<>();
        for (String command : commands) {
            normalized.add(command == null ? "" : command.trim().toUpperCase(Locale.ROOT));
        }
        return normalized;
    }
}
