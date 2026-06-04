package com.zuomagai.redisproxy.controlplane.model.observability;

public class ObservabilityTarget {
    private String proxyId;
    private String group = "default";
    private String advertiseIp = "";
    private int advertisePort;
    private String adminUrl;
    private String dataplane;
    private String cluster = "";
    private int pollIntervalSeconds = 15;
    private String serviceNamespace = "redis-proxy";
    private String serviceName = "redis-proxy-dataplane";
    private String serviceInstanceId = "";
    private String deploymentEnvironmentName = "";
    private String registrationSource = "manual";
    private String lastHeartbeatAt = "";
    private int heartbeatTtlSeconds = 45;

    public String getProxyId() { return proxyId; }
    public void setProxyId(String proxyId) { this.proxyId = proxyId; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public String getAdvertiseIp() { return advertiseIp; }
    public void setAdvertiseIp(String advertiseIp) { this.advertiseIp = advertiseIp; }
    public int getAdvertisePort() { return advertisePort; }
    public void setAdvertisePort(int advertisePort) { this.advertisePort = advertisePort; }
    public String getAdminUrl() { return adminUrl; }
    public void setAdminUrl(String adminUrl) { this.adminUrl = adminUrl; }
    public String getDataplane() { return dataplane; }
    public void setDataplane(String dataplane) { this.dataplane = dataplane; }
    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }
    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
    public String getServiceNamespace() { return serviceNamespace; }
    public void setServiceNamespace(String serviceNamespace) { this.serviceNamespace = serviceNamespace; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getServiceInstanceId() { return serviceInstanceId; }
    public void setServiceInstanceId(String serviceInstanceId) { this.serviceInstanceId = serviceInstanceId; }
    public String getDeploymentEnvironmentName() { return deploymentEnvironmentName; }
    public void setDeploymentEnvironmentName(String deploymentEnvironmentName) { this.deploymentEnvironmentName = deploymentEnvironmentName; }
    public String getRegistrationSource() { return registrationSource; }
    public void setRegistrationSource(String registrationSource) { this.registrationSource = registrationSource; }
    public String getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(String lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public int getHeartbeatTtlSeconds() { return heartbeatTtlSeconds; }
    public void setHeartbeatTtlSeconds(int heartbeatTtlSeconds) { this.heartbeatTtlSeconds = heartbeatTtlSeconds; }
}
