package com.zuomagai.redisproxy.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zuomagai.redisproxy.controlplane.model.ClusterSwitchPlan;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class MemoryClusterSwitchPlanRepository implements ClusterSwitchPlanRepository {
    private static final List<String> TERMINAL = List.of("COMPLETED", "ROLLED_BACK", "CANCELLED", "FAILED");

    private final ObjectMapper objectMapper;
    private final AtomicLong nextPlanId = new AtomicLong(1);
    private final Map<Long, ClusterSwitchPlan> plans = new ConcurrentHashMap<>();

    MemoryClusterSwitchPlanRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Override
    public ClusterSwitchPlan save(ClusterSwitchPlan plan) {
        ClusterSwitchPlan copy = copy(plan);
        Instant now = Instant.now();
        if (copy.getPlanId() == 0) {
            copy.setPlanId(nextPlanId.getAndIncrement());
            copy.setCreatedAt(now);
        }
        if (copy.getCreatedAt() == null) {
            copy.setCreatedAt(now);
        }
        copy.setUpdatedAt(now);
        plans.put(copy.getPlanId(), copy(copy));
        return copy(copy);
    }

    @Override
    public Optional<ClusterSwitchPlan> findById(long planId) {
        return Optional.ofNullable(plans.get(planId)).map(this::copy);
    }

    @Override
    public List<ClusterSwitchPlan> findAll() {
        return plans.values().stream()
                .sorted(Comparator.comparingLong(ClusterSwitchPlan::getPlanId))
                .map(this::copy)
                .toList();
    }

    @Override
    public Optional<ClusterSwitchPlan> findActiveByProxyGroupAndSourceCluster(String proxyGroup, String sourceCluster) {
        String normalizedGroup = proxyGroup == null || proxyGroup.isBlank() ? "default" : proxyGroup;
        return plans.values().stream()
                .filter(plan -> normalizedGroup.equals(plan.getProxyGroup()))
                .filter(plan -> sourceCluster.equals(plan.getSourceCluster()))
                .filter(plan -> !TERMINAL.contains(plan.getStatus()))
                .min(Comparator.comparingLong(ClusterSwitchPlan::getPlanId))
                .map(this::copy);
    }

    private ClusterSwitchPlan copy(ClusterSwitchPlan plan) {
        return objectMapper.convertValue(plan, ClusterSwitchPlan.class);
    }
}
