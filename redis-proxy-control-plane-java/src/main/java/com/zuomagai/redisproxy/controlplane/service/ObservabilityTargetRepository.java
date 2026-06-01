package com.zuomagai.redisproxy.controlplane.service;

import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityTarget;
import java.util.List;

public interface ObservabilityTargetRepository {
    void save(ObservabilityTarget target);

    List<ObservabilityTarget> findAll();

    void delete(String proxyId);
}
