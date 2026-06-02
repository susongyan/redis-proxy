package com.zuomagai.redisproxy.controlplane.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

public class CreateClusterSwitchPlanRequest {
    @NotBlank private String sourceCluster;
    @NotBlank private String targetCluster;
    private String mode = "STAGED";
    private List<Integer> steps = new ArrayList<>();
    @NotBlank private String operator;
    @NotBlank private String reason;
    @Valid private ProxyConfig.Cluster targetClusterDefinition;

    public String getSourceCluster() { return sourceCluster; }
    public void setSourceCluster(String sourceCluster) { this.sourceCluster = sourceCluster; }
    public String getTargetCluster() { return targetCluster; }
    public void setTargetCluster(String targetCluster) { this.targetCluster = targetCluster; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public List<Integer> getSteps() { return steps; }
    public void setSteps(List<Integer> steps) { this.steps = steps; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public ProxyConfig.Cluster getTargetClusterDefinition() { return targetClusterDefinition; }
    public void setTargetClusterDefinition(ProxyConfig.Cluster targetClusterDefinition) { this.targetClusterDefinition = targetClusterDefinition; }
}
