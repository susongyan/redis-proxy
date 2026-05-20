package com.example.redisproxy.controlplane.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class PublishRequest {
    @Valid @NotNull private ProxyConfig config;
    @NotBlank private String operator;
    @NotBlank private String reason;
    private String approvalStatus = "APPROVED";

    public ProxyConfig getConfig() { return config; }
    public void setConfig(ProxyConfig config) { this.config = config; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }
}
