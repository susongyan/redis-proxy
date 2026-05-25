package com.example.redisproxy.controlplane.service;

import com.example.redisproxy.controlplane.model.observability.ObservabilityTarget;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class MemoryObservabilityTargetRepository implements ObservabilityTargetRepository {
    private final Map<String, ObservabilityTarget> targets = new ConcurrentHashMap<>();

    @Override
    public void save(ObservabilityTarget target) {
        targets.put(target.getProxyId(), copyTarget(target));
    }

    @Override
    public List<ObservabilityTarget> findAll() {
        return targets.values().stream().map(MemoryObservabilityTargetRepository::copyTarget).toList();
    }

    @Override
    public void delete(String proxyId) {
        targets.remove(proxyId);
    }

    private static ObservabilityTarget copyTarget(ObservabilityTarget source) {
        ObservabilityTarget target = new ObservabilityTarget();
        target.setProxyId(source.getProxyId());
        target.setAdminUrl(source.getAdminUrl());
        target.setDataplane(source.getDataplane());
        target.setCluster(source.getCluster());
        target.setPollIntervalSeconds(source.getPollIntervalSeconds());
        target.setServiceNamespace(source.getServiceNamespace());
        target.setServiceName(source.getServiceName());
        target.setServiceInstanceId(source.getServiceInstanceId());
        target.setDeploymentEnvironmentName(source.getDeploymentEnvironmentName());
        return target;
    }
}
