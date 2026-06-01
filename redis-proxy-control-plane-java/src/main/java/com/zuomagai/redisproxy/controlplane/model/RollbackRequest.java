package com.zuomagai.redisproxy.controlplane.model;

import jakarta.validation.constraints.NotBlank;

public class RollbackRequest {
    private Long versionId;
    private Long routeEpoch;
    @NotBlank private String operator;
    @NotBlank private String reason;
    private String approvalStatus = "APPROVED";

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public Long getRouteEpoch() { return routeEpoch; }
    public void setRouteEpoch(Long routeEpoch) { this.routeEpoch = routeEpoch; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }
}
