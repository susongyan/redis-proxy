package com.zuomagai.redisproxy.controlplane.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ClusterSwitchPlan {
    private long planId;
    private String sourceCluster;
    private String targetCluster;
    private String mode = "STAGED";
    private String status = "CREATED";
    private List<Integer> steps = new ArrayList<>();
    private int currentStepIndex = -1;
    private String operator;
    private String reason;
    private long baselineVersionId;
    private ProxyConfig.Cluster targetClusterDefinition;
    private List<PublishedStep> publishedSteps = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public long getPlanId() { return planId; }
    public void setPlanId(long planId) { this.planId = planId; }
    public String getSourceCluster() { return sourceCluster; }
    public void setSourceCluster(String sourceCluster) { this.sourceCluster = sourceCluster; }
    public String getTargetCluster() { return targetCluster; }
    public void setTargetCluster(String targetCluster) { this.targetCluster = targetCluster; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<Integer> getSteps() { return steps; }
    public void setSteps(List<Integer> steps) { this.steps = steps == null ? new ArrayList<>() : steps; }
    public int getCurrentStepIndex() { return currentStepIndex; }
    public void setCurrentStepIndex(int currentStepIndex) { this.currentStepIndex = currentStepIndex; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public long getBaselineVersionId() { return baselineVersionId; }
    public void setBaselineVersionId(long baselineVersionId) { this.baselineVersionId = baselineVersionId; }
    public ProxyConfig.Cluster getTargetClusterDefinition() { return targetClusterDefinition; }
    public void setTargetClusterDefinition(ProxyConfig.Cluster targetClusterDefinition) { this.targetClusterDefinition = targetClusterDefinition; }
    public List<PublishedStep> getPublishedSteps() { return publishedSteps; }
    public void setPublishedSteps(List<PublishedStep> publishedSteps) { this.publishedSteps = publishedSteps == null ? new ArrayList<>() : publishedSteps; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Integer currentTrafficPercent() {
        if (currentStepIndex < 0 || currentStepIndex >= steps.size()) {
            return null;
        }
        return steps.get(currentStepIndex);
    }

    public static class PublishedStep {
        private int trafficPercent;
        private long versionId;
        private long routeEpoch;
        private String action;
        private Instant publishedAt;

        public int getTrafficPercent() { return trafficPercent; }
        public void setTrafficPercent(int trafficPercent) { this.trafficPercent = trafficPercent; }
        public long getVersionId() { return versionId; }
        public void setVersionId(long versionId) { this.versionId = versionId; }
        public long getRouteEpoch() { return routeEpoch; }
        public void setRouteEpoch(long routeEpoch) { this.routeEpoch = routeEpoch; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public Instant getPublishedAt() { return publishedAt; }
        public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    }
}
