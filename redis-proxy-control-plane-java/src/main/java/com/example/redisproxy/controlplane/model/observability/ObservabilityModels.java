package com.example.redisproxy.controlplane.model.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ObservabilityModels {
    private ObservabilityModels() {}

    public record TargetStatus(
            String proxyId,
            String adminUrl,
            String dataplane,
            String cluster,
            int pollIntervalSeconds,
            Map<String, String> resourceAttributes,
            boolean healthy,
            Instant lastCollectedAt,
            String lastError) {}

    public record Totals(
            double authTotal,
            double governanceRejectTotal,
            double governanceWarnTotal,
            double namespaceLimitRejectTotal,
            double keyGovernanceRejectTotal,
            double keyGovernanceDecisionTotal,
            double hotKeyObservedTotal,
            double hotKeyDroppedTotal,
            double hotKeyTracked,
            double largeKeyObservedTotal,
            double largeKeyDroppedTotal,
            double largeKeyUnsupportedTotal,
            double largeKeyTracked,
            double largeResponseTotal,
            double slowQueryObservedTotal,
            double slowQueryDroppedTotal,
            double slowQueryUnsupportedTotal,
            double slowQueryTracked) {}

    public record Summary(List<TargetStatus> targets, Totals totals) {}

    public record HotKeyObservation(
            String proxyId,
            String dataplane,
            String cluster,
            String namespace,
            String command,
            String key,
            long count,
            Map<String, String> resourceAttributes,
            Instant collectedAt,
            List<String> proxyIds) {}

    public record LargeKeyObservation(
            String proxyId,
            String dataplane,
            String cluster,
            String namespace,
            String command,
            String key,
            long count,
            int maxRequestBytes,
            int maxResponseBytes,
            Map<String, String> resourceAttributes,
            Instant collectedAt,
            List<String> proxyIds) {}

    public record SlowQueryObservation(
            String proxyId,
            String dataplane,
            String cluster,
            String namespace,
            String command,
            String key,
            long count,
            long maxEndToEndMillis,
            long maxBackendMillis,
            Map<String, String> resourceAttributes,
            Instant collectedAt,
            List<String> proxyIds) {}

    public record HistoryPoint(
            Instant timestamp,
            String metric,
            double value,
            Map<String, String> labels,
            Map<String, String> resourceAttributes) {}

    public record HistoryResponse(String metric, Instant from, Instant to, int stepSeconds, List<HistoryPoint> points) {}

    public record RouteSnapshotObservation(
            String proxyId,
            String dataplane,
            String adminUrl,
            boolean healthy,
            long epoch,
            String configHash,
            String lastApplyResult,
            long lastApplyTime,
            long lastPollTime,
            Instant collectedAt,
            String error) {}

    public record RouteConvergence(
            long expectedVersionId,
            long expectedRouteEpoch,
            String expectedConfigHash,
            String status,
            int total,
            int converged,
            int stale,
            int drift,
            int unreachable,
            List<RouteConvergenceInstance> proxies) {}

    public record RouteConvergenceInstance(
            String proxyId,
            String dataplane,
            String adminUrl,
            boolean healthy,
            long epoch,
            String configHash,
            String lastApplyResult,
            long lastApplyTime,
            long lastPollTime,
            Instant collectedAt,
            String status,
            String reason) {}
}
