package com.example.redisproxy.controlplane.service;

import com.example.redisproxy.controlplane.model.observability.ObservabilityTarget;
import java.util.List;

public interface ObservabilityTargetRepository {
    void save(ObservabilityTarget target);

    List<ObservabilityTarget> findAll();

    void delete(String proxyId);
}
