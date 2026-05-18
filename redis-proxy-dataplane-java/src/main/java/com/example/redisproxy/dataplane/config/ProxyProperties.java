package com.example.redisproxy.dataplane.config;

import java.util.ArrayList;
import java.util.List;
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
        boolean found = backends.clusters.stream().anyMatch(c -> routing.defaultCluster.equals(c.name));
        if (!found) {
            throw new IllegalArgumentException("default cluster not found: " + routing.defaultCluster);
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
        public String getDefaultCluster() { return defaultCluster; }
        public void setDefaultCluster(String defaultCluster) { this.defaultCluster = defaultCluster; }
        public long getRouteEpoch() { return routeEpoch; }
        public void setRouteEpoch(long routeEpoch) { this.routeEpoch = routeEpoch; }
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
