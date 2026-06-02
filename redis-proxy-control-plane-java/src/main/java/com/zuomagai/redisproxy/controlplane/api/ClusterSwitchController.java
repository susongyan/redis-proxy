package com.zuomagai.redisproxy.controlplane.api;

import com.zuomagai.redisproxy.controlplane.model.ClusterSwitchJumpRequest;
import com.zuomagai.redisproxy.controlplane.model.ClusterSwitchPlan;
import com.zuomagai.redisproxy.controlplane.model.CreateClusterSwitchPlanRequest;
import com.zuomagai.redisproxy.controlplane.service.ClusterSwitchService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClusterSwitchController {
    private final ClusterSwitchService clusterSwitchService;

    public ClusterSwitchController(ClusterSwitchService clusterSwitchService) {
        this.clusterSwitchService = clusterSwitchService;
    }

    @PostMapping(value = "/api/v1/cluster-switch/plans", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ClusterSwitchPlan create(@Valid @RequestBody CreateClusterSwitchPlanRequest request) {
        return clusterSwitchService.create(request);
    }

    @GetMapping("/api/v1/cluster-switch/plans")
    public List<ClusterSwitchPlan> plans() {
        return clusterSwitchService.plans();
    }

    @GetMapping("/api/v1/cluster-switch/plans/{planId}")
    public ClusterSwitchPlan plan(@PathVariable("planId") long planId) {
        return clusterSwitchService.plan(planId);
    }

    @PostMapping("/api/v1/cluster-switch/plans/{planId}/precheck")
    public ClusterSwitchPlan precheck(@PathVariable("planId") long planId) {
        return clusterSwitchService.precheck(planId);
    }

    @PostMapping("/api/v1/cluster-switch/plans/{planId}/start")
    public ClusterSwitchPlan start(@PathVariable("planId") long planId) {
        return clusterSwitchService.start(planId);
    }

    @PostMapping("/api/v1/cluster-switch/plans/{planId}/advance")
    public ClusterSwitchPlan advance(@PathVariable("planId") long planId) {
        return clusterSwitchService.advance(planId);
    }

    @PostMapping(value = "/api/v1/cluster-switch/plans/{planId}/jump", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ClusterSwitchPlan jump(@PathVariable("planId") long planId, @RequestBody ClusterSwitchJumpRequest request) {
        return clusterSwitchService.jump(planId, request);
    }

    @PostMapping(value = "/api/v1/cluster-switch/plans/{planId}/rollback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ClusterSwitchPlan rollback(@PathVariable("planId") long planId, @RequestBody(required = false) ClusterSwitchJumpRequest request) {
        return clusterSwitchService.rollback(planId, request);
    }

    @PostMapping("/api/v1/cluster-switch/plans/{planId}/cancel")
    public ClusterSwitchPlan cancel(@PathVariable("planId") long planId) {
        return clusterSwitchService.cancel(planId);
    }
}
