package com.zuomagai.redisproxy.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zuomagai.redisproxy.controlplane.model.ClusterSwitchJumpRequest;
import com.zuomagai.redisproxy.controlplane.model.ClusterSwitchPlan;
import com.zuomagai.redisproxy.controlplane.model.ConfigVersion;
import com.zuomagai.redisproxy.controlplane.model.CreateClusterSwitchPlanRequest;
import com.zuomagai.redisproxy.controlplane.model.ProxyConfig;
import com.zuomagai.redisproxy.controlplane.model.PublishRequest;
import com.zuomagai.redisproxy.controlplane.model.RollbackRequest;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.RouteConvergence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ClusterSwitchService {
    private static final List<Integer> DEFAULT_STEPS = List.of(0, 10, 25, 50, 100);
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "ROLLED_BACK", "CANCELLED", "FAILED");

    private final ConfigService configService;
    private final ObservabilityService observabilityService;
    private final ClusterSwitchPlanRepository repository;
    private final ObjectMapper objectMapper;

    @Autowired
    public ClusterSwitchService(
            ConfigService configService,
            ObservabilityService observabilityService,
            ClusterSwitchPlanRepository repository,
            ObjectMapper objectMapper) {
        this.configService = configService;
        this.observabilityService = observabilityService;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public ClusterSwitchService(ConfigService configService, ObservabilityService observabilityService, ObjectMapper objectMapper) {
        this(configService, observabilityService, new MemoryClusterSwitchPlanRepository(objectMapper), objectMapper);
    }

    public ClusterSwitchPlan create(CreateClusterSwitchPlanRequest request) {
        ProxyConfig current = configService.get();
        String proxyGroup = normalizeGroup(request.getProxyGroup());
        String source = required(request.getSourceCluster(), "sourceCluster");
        String target = required(request.getTargetCluster(), "targetCluster");
        String mode = normalizeMode(request.getMode());
        GroupSwitchTarget group = groupSwitchTarget(current, proxyGroup);
        if (!source.equals(group.routing().getDefaultCluster())) {
            throw new IllegalArgumentException("sourceCluster must match selected proxyGroup defaultCluster");
        }
        if (repository.findActiveByProxyGroupAndSourceCluster(proxyGroup, source).isPresent()) {
            throw new IllegalArgumentException("active cluster switch plan already exists for proxyGroup " + proxyGroup + " and source cluster " + source);
        }
        if (!clusterExists(current, source) || !group.clusterEnabled(source)) {
            throw new IllegalArgumentException("sourceCluster is not enabled for proxyGroup " + proxyGroup + ": " + source);
        }
        if (!group.clusterEnabled(target) && request.getTargetClusterDefinition() == null) {
            throw new IllegalArgumentException("targetCluster must be enabled for proxyGroup or targetClusterDefinition must be provided");
        }
        if (request.getTargetClusterDefinition() != null && !target.equals(request.getTargetClusterDefinition().getName())) {
            throw new IllegalArgumentException("targetClusterDefinition.name must match targetCluster");
        }
        ClusterSwitchPlan plan = new ClusterSwitchPlan();
        plan.setProxyGroup(proxyGroup);
        plan.setSourceCluster(source);
        plan.setTargetCluster(target);
        plan.setMode(mode);
        plan.setStatus("CREATED");
        plan.setSteps(steps(mode, request.getSteps()));
        plan.setOperator(required(request.getOperator(), "operator"));
        plan.setReason(required(request.getReason(), "reason"));
        plan.setBaselineVersionId(configService.routeStatus().currentVersionId());
        plan.setTargetClusterDefinition(copyCluster(request.getTargetClusterDefinition()));
        return repository.save(plan);
    }

    public List<ClusterSwitchPlan> plans() {
        return repository.findAll().stream()
                .sorted(Comparator.comparingLong(ClusterSwitchPlan::getPlanId))
                .toList();
    }

    public ClusterSwitchPlan plan(long planId) {
        return repository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("cluster switch plan not found: " + planId));
    }

    public ClusterSwitchPlan precheck(long planId) {
        ClusterSwitchPlan plan = plan(planId);
        ensureStatus(plan, "CREATED", "PRECHECKED");
        validatePlanAgainstCurrent(plan);
        requireConverged();
        plan.setStatus("PRECHECKED");
        return repository.save(plan);
    }

    public ClusterSwitchPlan start(long planId) {
        ClusterSwitchPlan plan = plan(planId);
        ensureStatus(plan, "CREATED", "PRECHECKED");
        validatePlanAgainstCurrent(plan);
        requireConverged();
        int percent = plan.getSteps().getFirst();
        publishTraffic(plan, percent, "START");
        plan.setCurrentStepIndex(0);
        plan.setStatus(percent >= 100 ? "COMPLETED" : "RUNNING");
        return repository.save(plan);
    }

    public ClusterSwitchPlan advance(long planId) {
        ClusterSwitchPlan plan = plan(planId);
        ensureStatus(plan, "RUNNING");
        requireConverged();
        int next = plan.getCurrentStepIndex() + 1;
        if (next >= plan.getSteps().size()) {
            throw new IllegalArgumentException("cluster switch plan has no remaining steps");
        }
        int percent = plan.getSteps().get(next);
        publishTraffic(plan, percent, "ADVANCE");
        plan.setCurrentStepIndex(next);
        plan.setStatus(percent >= 100 ? "COMPLETED" : "RUNNING");
        return repository.save(plan);
    }

    public ClusterSwitchPlan jump(long planId, ClusterSwitchJumpRequest request) {
        ClusterSwitchPlan plan = plan(planId);
        ensureNotTerminal(plan);
        requireConverged();
        int percent = request.getTrafficPercent();
        validatePercent(percent);
        publishTraffic(plan, percent, "JUMP", request.getOperator(), request.getReason());
        int index = plan.getSteps().indexOf(percent);
        if (index < 0) {
            List<Integer> nextSteps = new ArrayList<>(plan.getSteps());
            nextSteps.add(percent);
            nextSteps = normalizeSteps(nextSteps);
            plan.setSteps(nextSteps);
            index = nextSteps.indexOf(percent);
        }
        plan.setCurrentStepIndex(index);
        plan.setStatus(percent >= 100 ? "COMPLETED" : "RUNNING");
        return repository.save(plan);
    }

    public ClusterSwitchPlan rollback(long planId, ClusterSwitchJumpRequest request) {
        ClusterSwitchPlan plan = plan(planId);
        if (List.of("ROLLED_BACK", "CANCELLED", "FAILED").contains(plan.getStatus())) {
            throw new IllegalArgumentException("cluster switch plan is terminal: " + plan.getStatus());
        }
        requireConverged();
        RollbackRequest rollback = new RollbackRequest();
        rollback.setVersionId(plan.getBaselineVersionId());
        rollback.setOperator(operator(plan, request));
        rollback.setReason(reason(plan, request, "cluster switch rollback"));
        ConfigVersion version = configService.rollback(rollback);
        recordPublished(plan, -1, version, "ROLLBACK");
        plan.setStatus("ROLLED_BACK");
        return repository.save(plan);
    }

    public ClusterSwitchPlan cancel(long planId) {
        ClusterSwitchPlan plan = plan(planId);
        ensureStatus(plan, "CREATED", "PRECHECKED");
        plan.setStatus("CANCELLED");
        return repository.save(plan);
    }

    private void publishTraffic(ClusterSwitchPlan plan, int percent, String action) {
        publishTraffic(plan, percent, action, null, null);
    }

    private void publishTraffic(ClusterSwitchPlan plan, int percent, String action, String operator, String reason) {
        validatePercent(percent);
        ProxyConfig next = copyConfig(configService.get());
        ensureTargetCluster(next, plan);
        next.getRouting().setRouteEpoch(next.getRouting().getRouteEpoch() + 1);
        GroupSwitchTarget group = groupSwitchTarget(next, plan.getProxyGroup());
        if (group.legacyDefault()) {
            removeSwitchRule(next.getRouting(), plan.getPlanId());
            if (percent >= 100 || "FULL".equals(plan.getMode())) {
                next.getRouting().setDefaultCluster(plan.getTargetCluster());
            } else {
                next.getRouting().setDefaultCluster(plan.getSourceCluster());
                List<ProxyConfig.RouteRule> rules = new ArrayList<>(next.getRouting().getRules());
                rules.add(0, switchRule(plan, percent));
                next.getRouting().setRules(rules);
            }
        } else {
            ensureTargetEnabledForGroup(group.group(), plan.getTargetCluster());
            group.routing().setRouteEpoch(next.getRouting().getRouteEpoch());
            removeSwitchRule(group.routing(), plan.getPlanId());
            if (percent >= 100 || "FULL".equals(plan.getMode())) {
                group.routing().setDefaultCluster(plan.getTargetCluster());
            } else {
                group.routing().setDefaultCluster(plan.getSourceCluster());
                List<ProxyConfig.RouteRule> rules = new ArrayList<>(group.routing().getRules());
                rules.add(0, switchRule(plan, percent));
                group.routing().setRules(rules);
            }
        }
        PublishRequest publish = new PublishRequest();
        publish.setConfig(next);
        publish.setOperator(operator(plan, operator));
        publish.setReason(reason(plan, reason, "cluster switch " + action.toLowerCase(Locale.ROOT) + " to " + percent + "%"));
        ConfigVersion version = configService.publish(publish);
        recordPublished(plan, percent, version, action);
    }

    private void validatePlanAgainstCurrent(ClusterSwitchPlan plan) {
        ProxyConfig current = configService.get();
        GroupSwitchTarget group = groupSwitchTarget(current, plan.getProxyGroup());
        if (!clusterExists(current, plan.getSourceCluster()) || !group.clusterEnabled(plan.getSourceCluster())) {
            throw new IllegalArgumentException("sourceCluster is not enabled for proxyGroup " + plan.getProxyGroup() + ": " + plan.getSourceCluster());
        }
        if (!plan.getSourceCluster().equals(group.routing().getDefaultCluster())) {
            throw new IllegalArgumentException("sourceCluster must match selected proxyGroup defaultCluster before switch starts");
        }
        if (!group.clusterEnabled(plan.getTargetCluster()) && plan.getTargetClusterDefinition() == null) {
            throw new IllegalArgumentException("targetCluster must be enabled for proxyGroup or targetClusterDefinition must be provided");
        }
    }

    private void requireConverged() {
        RouteConvergence convergence = observabilityService.routeConvergence(configService.routeStatus());
        if (!"CONVERGED".equals(convergence.status())) {
            throw new IllegalStateException("route convergence must be CONVERGED before cluster switch operation, current=" + convergence.status());
        }
    }

    private ProxyConfig.RouteRule switchRule(ClusterSwitchPlan plan, int percent) {
        ProxyConfig.RouteRule rule = new ProxyConfig.RouteRule();
        rule.setName(ruleName(plan.getPlanId()));
        rule.setCluster(plan.getTargetCluster());
        rule.setMatchAll(true);
        rule.setTrafficPercent(percent);
        return rule;
    }

    private void removeSwitchRule(ProxyConfig.Routing routing, long planId) {
        String name = ruleName(planId);
        routing.setRules(routing.getRules().stream()
                .filter(rule -> !name.equals(rule.getName()))
                .toList());
    }

    private String ruleName(long planId) {
        return "cluster-switch-" + planId;
    }

    private void ensureTargetCluster(ProxyConfig config, ClusterSwitchPlan plan) {
        if (clusterExists(config, plan.getTargetCluster())) {
            return;
        }
        if (plan.getTargetClusterDefinition() == null) {
            throw new IllegalArgumentException("targetCluster does not exist: " + plan.getTargetCluster());
        }
        List<ProxyConfig.Cluster> clusters = new ArrayList<>(config.getBackends().getClusters());
        clusters.add(copyCluster(plan.getTargetClusterDefinition()));
        config.getBackends().setClusters(clusters);
    }

    private void ensureTargetEnabledForGroup(ProxyConfig.ProxyGroup group, String targetCluster) {
        List<String> enabled = new ArrayList<>(group.getEnabledClusters());
        if (!enabled.contains(targetCluster)) {
            enabled.add(targetCluster);
            group.setEnabledClusters(enabled);
        }
    }

    private boolean clusterExists(ProxyConfig config, String name) {
        return config.getBackends().getClusters().stream().anyMatch(cluster -> name.equals(cluster.getName()));
    }

    private GroupSwitchTarget groupSwitchTarget(ProxyConfig config, String proxyGroup) {
        String normalizedGroup = normalizeGroup(proxyGroup);
        if (config.getProxyGroups().isEmpty()) {
            if (!"default".equals(normalizedGroup)) {
                throw new IllegalArgumentException("proxyGroup does not exist: " + normalizedGroup);
            }
            Set<String> clusters = config.getBackends().getClusters().stream()
                    .map(ProxyConfig.Cluster::getName)
                    .collect(java.util.stream.Collectors.toSet());
            return new GroupSwitchTarget(null, config.getRouting(), clusters, true);
        }
        ProxyConfig.ProxyGroup group = config.getProxyGroups().stream()
                .filter(candidate -> normalizedGroup.equals(normalizeGroup(candidate.getName())))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("proxyGroup does not exist: " + normalizedGroup));
        if (group.getRouting() == null) {
            ProxyConfig.Routing routing = copyConfig(config).getRouting();
            routing.setDefaultCluster(group.getEnabledClusters().isEmpty() ? config.getRouting().getDefaultCluster() : group.getEnabledClusters().getFirst());
            group.setRouting(routing);
        }
        if (group.getRouting().getRules() == null) {
            group.getRouting().setRules(List.of());
        }
        return new GroupSwitchTarget(group, group.getRouting(), Set.copyOf(group.getEnabledClusters()), false);
    }

    private String normalizeGroup(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }

    private void recordPublished(ClusterSwitchPlan plan, int percent, ConfigVersion version, String action) {
        ClusterSwitchPlan.PublishedStep step = new ClusterSwitchPlan.PublishedStep();
        step.setTrafficPercent(percent);
        step.setVersionId(version.versionId());
        step.setRouteEpoch(version.routeEpoch());
        step.setAction(action);
        step.setPublishedAt(Instant.now());
        List<ClusterSwitchPlan.PublishedStep> steps = new ArrayList<>(plan.getPublishedSteps());
        steps.add(step);
        plan.setPublishedSteps(steps);
    }

    private List<Integer> steps(String mode, List<Integer> requested) {
        if ("FULL".equals(mode)) {
            return List.of(100);
        }
        List<Integer> values = requested == null || requested.isEmpty() ? DEFAULT_STEPS : requested;
        values = normalizeSteps(values);
        if (!values.contains(100)) {
            values = new ArrayList<>(values);
            values.add(100);
            values = normalizeSteps(values);
        }
        return values;
    }

    private List<Integer> normalizeSteps(List<Integer> values) {
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        for (Integer value : values) {
            if (value == null) {
                continue;
            }
            validatePercent(value);
            set.add(value);
        }
        if (set.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
        return set.stream().sorted().toList();
    }

    private void validatePercent(int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("trafficPercent must be between 0 and 100");
        }
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null || mode.isBlank() ? "STAGED" : mode.trim().toUpperCase(Locale.ROOT);
        if (!List.of("STAGED", "FULL").contains(normalized)) {
            throw new IllegalArgumentException("mode must be STAGED or FULL");
        }
        return normalized;
    }

    private void ensureStatus(ClusterSwitchPlan plan, String... allowed) {
        for (String status : allowed) {
            if (status.equals(plan.getStatus())) {
                return;
            }
        }
        throw new IllegalArgumentException("cluster switch plan status " + plan.getStatus() + " does not allow this operation");
    }

    private void ensureNotTerminal(ClusterSwitchPlan plan) {
        if (TERMINAL.contains(plan.getStatus())) {
            throw new IllegalArgumentException("cluster switch plan is terminal: " + plan.getStatus());
        }
    }

    private String operator(ClusterSwitchPlan plan, ClusterSwitchJumpRequest request) {
        return operator(plan, request == null ? null : request.getOperator());
    }

    private String operator(ClusterSwitchPlan plan, String value) {
        return value == null || value.isBlank() ? plan.getOperator() : value;
    }

    private String reason(ClusterSwitchPlan plan, ClusterSwitchJumpRequest request, String fallback) {
        return reason(plan, request == null ? null : request.getReason(), fallback);
    }

    private String reason(ClusterSwitchPlan plan, String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        return plan.getReason() == null || plan.getReason().isBlank() ? fallback : plan.getReason();
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private ProxyConfig copyConfig(ProxyConfig config) {
        return objectMapper.convertValue(config, ProxyConfig.class);
    }

    private ProxyConfig.Cluster copyCluster(ProxyConfig.Cluster cluster) {
        return cluster == null ? null : objectMapper.convertValue(cluster, ProxyConfig.Cluster.class);
    }

    private record GroupSwitchTarget(
            ProxyConfig.ProxyGroup group,
            ProxyConfig.Routing routing,
            Set<String> enabledClusters,
            boolean legacyDefault) {
        boolean clusterEnabled(String cluster) {
            return enabledClusters.contains(cluster);
        }
    }
}
