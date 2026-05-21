package com.example.redisproxy.dataplane.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "proxy")
public class ProxyProperties {
    private Server server = new Server();
    private Admin admin = new Admin();
    private String mode = "standalone";
    private Backends backends = new Backends();
    private Routing routing = new Routing();
    private Limits limits = new Limits();
    private ControlPlane controlPlane = new ControlPlane();
    private Governance governance = new Governance();

    public void validate() {
        if (!"standalone".equals(mode) && !"cluster".equals(mode)) {
            throw new IllegalArgumentException("unsupported mode: " + mode);
        }
        if (routing.defaultCluster == null || routing.defaultCluster.isBlank()) {
            throw new IllegalArgumentException("routing.defaultCluster is required");
        }
        if (routing.clusterSlotsRefreshIntervalSeconds < 0) {
            throw new IllegalArgumentException("routing.clusterSlotsRefreshIntervalSeconds must be >= 0");
        }
        if (routing.routeEpoch < 0) {
            throw new IllegalArgumentException("routing.routeEpoch must be >= 0");
        }
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
            if ((rule.keyPrefix == null || rule.keyPrefix.isBlank()) && (rule.hashTag == null || rule.hashTag.isBlank())) {
                throw new IllegalArgumentException("routing rule " + rule.name + " must set keyPrefix or hashTag");
            }
        }
    }

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
    public Governance getGovernance() { return governance; }
    public void setGovernance(Governance governance) { this.governance = governance; }

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
        private List<RouteRule> rules = new ArrayList<>();
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
        private String name;
        private String cluster;
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
        private int maxRequestBytes = 10 * 1024 * 1024;
        private int maxResponseBytes = 100 * 1024 * 1024;
        public int getMaxPipelineDepth() { return maxPipelineDepth; }
        public void setMaxPipelineDepth(int maxPipelineDepth) { this.maxPipelineDepth = maxPipelineDepth; }
        public int getMaxRequestBytes() { return maxRequestBytes; }
        public void setMaxRequestBytes(int maxRequestBytes) { this.maxRequestBytes = maxRequestBytes; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    }

    public static class Governance {
        private boolean enabled;
        private boolean requireAuth;
        private CommandPolicy commandPolicy = new CommandPolicy();
        private List<Namespace> namespaces = new ArrayList<>();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isRequireAuth() { return requireAuth; }
        public void setRequireAuth(boolean requireAuth) { this.requireAuth = requireAuth; }
        public CommandPolicy getCommandPolicy() { return commandPolicy; }
        public void setCommandPolicy(CommandPolicy commandPolicy) { this.commandPolicy = commandPolicy; }
        public List<Namespace> getNamespaces() { return namespaces; }
        public void setNamespaces(List<Namespace> namespaces) { this.namespaces = namespaces; }

        void applyDefaults() {
            if (enabled && !requireAuth) {
                requireAuth = true;
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

        void validate() {
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
