package com.zuomagai.redisproxy.controlplane.service;

import com.zuomagai.redisproxy.controlplane.model.ClusterSwitchPlan;
import java.util.List;
import java.util.Optional;

public interface ClusterSwitchPlanRepository {
    ClusterSwitchPlan save(ClusterSwitchPlan plan);

    Optional<ClusterSwitchPlan> findById(long planId);

    List<ClusterSwitchPlan> findAll();

    Optional<ClusterSwitchPlan> findActiveBySourceCluster(String sourceCluster);
}
