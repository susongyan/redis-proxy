package com.example.redisproxy.dataplane.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "proxy")
public class ProxyProperties {
    private Server server = new Server();
    private Admin admin = new Admin();
    private String mode = "standalone";
    private Backends backends = new Backends();
    private Routing routing = new Routing();
    private Limits limits = new Limits();

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
}
