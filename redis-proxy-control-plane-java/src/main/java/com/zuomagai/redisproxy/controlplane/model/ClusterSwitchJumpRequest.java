package com.zuomagai.redisproxy.controlplane.model;

public class ClusterSwitchJumpRequest {
    private int trafficPercent;
    private String operator;
    private String reason;

    public int getTrafficPercent() { return trafficPercent; }
    public void setTrafficPercent(int trafficPercent) { this.trafficPercent = trafficPercent; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
