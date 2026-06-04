package com.zuomagai.redisproxy.controlplane.service;

import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.HotKeyObservation;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.HistoryPoint;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.HistoryResponse;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.LargeKeyObservation;
import com.zuomagai.redisproxy.controlplane.model.RouteStatus;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.RouteConvergence;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.RouteConvergenceInstance;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.RouteSnapshotObservation;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.SlowQueryObservation;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.Summary;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.TargetStatus;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.Totals;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityProperties;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ObservabilityService {
    private static final int DEFAULT_POLL_INTERVAL_SECONDS = 15;
    private static final int DEFAULT_HEARTBEAT_TTL_SECONDS = 45;
    private static final int MIN_POLL_INTERVAL_SECONDS = 1;
    private static final int MAX_POLL_INTERVAL_SECONDS = 300;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ObservabilityProperties properties;
    private final ObservabilityTargetRepository targetRepository;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ObservabilityTarget> targets = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> schedules = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Snapshot>> history = new ConcurrentHashMap<>();

    @Autowired
    public ObservabilityService(ObjectMapper objectMapper, ObservabilityProperties properties, ObservabilityTargetRepository targetRepository) {
        this(objectMapper, properties, targetRepository, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    public ObservabilityService(ObjectMapper objectMapper) {
        this(objectMapper, new ObservabilityProperties(), new MemoryObservabilityTargetRepository(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    public ObservabilityService(ObjectMapper objectMapper, ObservabilityProperties properties) {
        this(objectMapper, properties, new MemoryObservabilityTargetRepository(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    ObservabilityService(ObjectMapper objectMapper, ObservabilityProperties properties, HttpClient httpClient) {
        this(objectMapper, properties, new MemoryObservabilityTargetRepository(), httpClient);
    }

    ObservabilityService(ObjectMapper objectMapper, ObservabilityProperties properties, ObservabilityTargetRepository targetRepository, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.properties = properties == null ? new ObservabilityProperties() : properties;
        this.targetRepository = targetRepository == null ? new MemoryObservabilityTargetRepository() : targetRepository;
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "observability-collector");
            thread.setDaemon(true);
            return thread;
        });
        for (ObservabilityTarget target : this.targetRepository.findAll()) {
            scheduleTarget(normalize(target));
        }
    }

    public TargetStatus register(ObservabilityTarget target) {
        ObservabilityTarget normalized = normalize(target);
        normalized.setLastHeartbeatAt(Instant.now().toString());
        targetRepository.save(normalized);
        scheduleTarget(normalized);
        return statusFor(normalized.getProxyId());
    }

    private void scheduleTarget(ObservabilityTarget normalized) {
        ScheduledFuture<?> previous = schedules.remove(normalized.getProxyId());
        if (previous != null) {
            previous.cancel(false);
        }
        targets.put(normalized.getProxyId(), copyTarget(normalized));
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> collectSafely(normalized.getProxyId()),
                0,
                normalized.getPollIntervalSeconds(),
                TimeUnit.SECONDS);
        schedules.put(normalized.getProxyId(), future);
        snapshots.putIfAbsent(normalized.getProxyId(), Snapshot.empty(normalized));
    }

    public List<TargetStatus> targets() {
        return targets.keySet().stream()
                .sorted()
                .map(this::statusFor)
                .toList();
    }

    public void delete(String proxyId) {
        targetRepository.delete(proxyId);
        ScheduledFuture<?> future = schedules.remove(proxyId);
        if (future != null) {
            future.cancel(false);
        }
        targets.remove(proxyId);
        snapshots.remove(proxyId);
        history.remove(proxyId);
    }

    public Summary summary() {
        List<Snapshot> values = snapshots.values().stream().toList();
        Totals totals = new Totals(
                sum(values, "redis_proxy_auth_total"),
                sum(values, "redis_proxy_governance_reject_total"),
                sum(values, "redis_proxy_governance_warn_total"),
                sum(values, "redis_proxy_namespace_limit_reject_total"),
                sum(values, "redis_proxy_key_governance_reject_total"),
                sum(values, "redis_proxy_key_governance_decisions_total"),
                sum(values, "redis_proxy_hot_key_observed_total"),
                sum(values, "redis_proxy_hot_key_dropped_total"),
                gaugeSum(values, "redis_proxy_hot_key_tracked_keys"),
                sum(values, "redis_proxy_large_key_observed_total"),
                sum(values, "redis_proxy_large_key_dropped_total"),
                sum(values, "redis_proxy_large_key_unsupported_total"),
                gaugeSum(values, "redis_proxy_large_key_tracked_keys"),
                sum(values, "redis_proxy_large_response_total"),
                sum(values, "redis_proxy_slow_query_observed_total"),
                sum(values, "redis_proxy_slow_query_dropped_total"),
                sum(values, "redis_proxy_slow_query_unsupported_total"),
                gaugeSum(values, "redis_proxy_slow_query_tracked_keys"));
        return new Summary(targets(), totals);
    }

    public List<HotKeyObservation> hotKeys(String proxyId, String namespace, String command, int limit) {
        return snapshots.values().stream()
                .flatMap(snapshot -> snapshot.hotKeys().stream())
                .filter(item -> matches(proxyId, item.proxyId()))
                .filter(item -> matches(namespace, item.namespace()))
                .filter(item -> matches(command, item.command()))
                .collect(() -> new LinkedHashMap<String, HotKeyObservation>(), ObservabilityService::mergeHotKey, Map::putAll)
                .values().stream()
                .sorted(Comparator.comparingLong(HotKeyObservation::count).reversed()
                        .thenComparing(HotKeyObservation::namespace)
                        .thenComparing(HotKeyObservation::command)
                        .thenComparing(HotKeyObservation::key))
                .limit(boundLimit(limit))
                .toList();
    }

    public List<LargeKeyObservation> largeKeys(String proxyId, String namespace, String command, int limit) {
        return snapshots.values().stream()
                .flatMap(snapshot -> snapshot.largeKeys().stream())
                .filter(item -> matches(proxyId, item.proxyId()))
                .filter(item -> matches(namespace, item.namespace()))
                .filter(item -> matches(command, item.command()))
                .collect(() -> new LinkedHashMap<String, LargeKeyObservation>(), ObservabilityService::mergeLargeKey, Map::putAll)
                .values().stream()
                .sorted(Comparator.comparingInt((LargeKeyObservation item) -> Math.max(item.maxRequestBytes(), item.maxResponseBytes())).reversed()
                        .thenComparing(LargeKeyObservation::count, Comparator.reverseOrder())
                        .thenComparing(LargeKeyObservation::namespace)
                        .thenComparing(LargeKeyObservation::command)
                        .thenComparing(LargeKeyObservation::key))
                .limit(boundLimit(limit))
                .toList();
    }

    public List<SlowQueryObservation> slowQueries(String proxyId, String namespace, String command, int limit) {
        return snapshots.values().stream()
                .flatMap(snapshot -> snapshot.slowQueries().stream())
                .filter(item -> matches(proxyId, item.proxyId()))
                .filter(item -> matches(namespace, item.namespace()))
                .filter(item -> matches(command, item.command()))
                .collect(() -> new LinkedHashMap<String, SlowQueryObservation>(), ObservabilityService::mergeSlowQuery, Map::putAll)
                .values().stream()
                .sorted(Comparator.comparingLong((SlowQueryObservation item) -> Math.max(item.maxEndToEndMillis(), item.maxBackendMillis())).reversed()
                        .thenComparing(SlowQueryObservation::count, Comparator.reverseOrder())
                        .thenComparing(SlowQueryObservation::namespace)
                        .thenComparing(SlowQueryObservation::command)
                        .thenComparing(SlowQueryObservation::key))
                .limit(boundLimit(limit))
                .toList();
    }

    public HistoryResponse history(String metric, Instant from, Instant to, int stepSeconds, String proxyId, String cluster, String dataplane) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(1)) : from;
        int step = stepSeconds <= 0 ? 60 : Math.max(1, stepSeconds);
        List<Snapshot> ordered = history.values().stream()
                .flatMap(List::stream)
                .filter(snapshot -> snapshot.collectedAt() != null && !snapshot.collectedAt().isBefore(start) && !snapshot.collectedAt().isAfter(end))
                .filter(snapshot -> matches(proxyId, snapshot.target().getProxyId()))
                .filter(snapshot -> matches(cluster, snapshot.target().getCluster()))
                .filter(snapshot -> matches(dataplane, snapshot.target().getDataplane()))
                .sorted(Comparator.comparing(Snapshot::collectedAt))
                .toList();
        Map<String, Double> previousCounters = new HashMap<>();
        Map<String, MutableHistoryPoint> buckets = new LinkedHashMap<>();
        for (Snapshot snapshot : ordered) {
            Instant bucket = bucketTime(snapshot.collectedAt(), step);
            Map<String, String> resource = resourceAttributes(snapshot.target());
            for (MetricSample sample : snapshot.samples()) {
                if (metric != null && !metric.isBlank() && !sample.name().equals(metric)) {
                    continue;
                }
                boolean counter = sample.name().endsWith("_total");
                String seriesKey = snapshot.target().getProxyId() + "\u0000" + sample.name() + "\u0000" + sample.labels();
                double value = sample.value();
                if (counter) {
                    Double previous = previousCounters.put(seriesKey, sample.value());
                    value = previous == null ? 0.0 : Math.max(0.0, sample.value() - previous);
                }
                String bucketKey = bucket + "\u0000" + sample.name() + "\u0000" + sample.labels() + "\u0000" + resource;
                MutableHistoryPoint point = buckets.computeIfAbsent(bucketKey, ignored -> new MutableHistoryPoint(bucket, sample.name(), sample.labels(), resource, counter));
                point.add(value);
            }
        }
        List<HistoryPoint> points = buckets.values().stream()
                .map(MutableHistoryPoint::toPoint)
                .sorted(Comparator.comparing(HistoryPoint::timestamp).thenComparing(HistoryPoint::metric))
                .toList();
        return new HistoryResponse(metric == null ? "" : metric, start, end, step, points);
    }

    public String prometheus() {
        Summary summary = summary();
        StringBuilder out = new StringBuilder();
        writeGauge(out, "redis_proxy_control_plane_auth_total", summary.totals().authTotal());
        writeGauge(out, "redis_proxy_control_plane_governance_reject_total", summary.totals().governanceRejectTotal());
        writeGauge(out, "redis_proxy_control_plane_namespace_limit_reject_total", summary.totals().namespaceLimitRejectTotal());
        writeGauge(out, "redis_proxy_control_plane_key_governance_reject_total", summary.totals().keyGovernanceRejectTotal());
        writeGauge(out, "redis_proxy_control_plane_hot_key_tracked_keys", summary.totals().hotKeyTracked());
        writeGauge(out, "redis_proxy_control_plane_large_key_tracked_keys", summary.totals().largeKeyTracked());
        writeGauge(out, "redis_proxy_control_plane_large_response_total", summary.totals().largeResponseTotal());
        writeGauge(out, "redis_proxy_control_plane_slow_query_observed_total", summary.totals().slowQueryObservedTotal());
        writeGauge(out, "redis_proxy_control_plane_slow_query_tracked_keys", summary.totals().slowQueryTracked());
        writeGauge(out, "redis_proxy_control_plane_observability_targets", summary.targets().size());
        writeGauge(out, "redis_proxy_control_plane_observability_unreachable_targets", summary.targets().stream().filter(target -> !target.healthy()).count());
        return out.toString();
    }

    public void collectNow(String proxyId) {
        collectSafely(proxyId);
    }

    public RouteConvergence routeConvergence(RouteStatus expected) {
        List<RouteConvergenceInstance> instances = targets.values().stream()
                .map(target -> convergenceInstance(target, expected))
                .sorted(Comparator.comparing(RouteConvergenceInstance::proxyId))
                .toList();
        int converged = 0;
        int stale = 0;
        int drift = 0;
        int unreachable = 0;
        for (RouteConvergenceInstance instance : instances) {
            switch (instance.status()) {
                case "CONVERGED" -> converged++;
                case "STALE" -> stale++;
                case "DRIFT" -> drift++;
                case "UNREACHABLE" -> unreachable++;
                default -> {}
            }
        }
        String status;
        if (unreachable > 0) {
            status = "UNREACHABLE";
        } else if (drift > 0) {
            status = "DRIFT";
        } else if (stale > 0) {
            status = converged > 0 ? "PARTIAL" : "STALE";
        } else if (converged == instances.size()) {
            status = "CONVERGED";
        } else {
            status = "PARTIAL";
        }
        return new RouteConvergence(
                expected.expectedVersionId(),
                expected.expectedRouteEpoch(),
                expected.expectedConfigHash(),
                status,
                instances.size(),
                converged,
                stale,
                drift,
                unreachable,
                instances);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    private TargetStatus statusFor(String proxyId) {
        ObservabilityTarget target = targets.get(proxyId);
        Snapshot snapshot = snapshots.get(proxyId);
        if (target == null && snapshot == null) {
            throw new IllegalArgumentException("observability target not found: " + proxyId);
        }
        ObservabilityTarget source = target == null ? snapshot.target() : target;
        return new TargetStatus(
                source.getProxyId(),
                source.getGroup(),
                source.getAdvertiseIp(),
                source.getAdvertisePort(),
                source.getAdminUrl(),
                source.getDataplane(),
                source.getCluster(),
                source.getPollIntervalSeconds(),
                resourceAttributes(source),
                parseInstant(source.getLastHeartbeatAt()).orElse(null),
                source.getHeartbeatTtlSeconds(),
                source.getRegistrationSource(),
                snapshot != null && snapshot.healthy() && !heartbeatExpired(source),
                snapshot == null ? null : snapshot.collectedAt(),
                snapshot == null ? "not collected" : snapshot.error());
    }

    private RouteConvergenceInstance convergenceInstance(ObservabilityTarget target, RouteStatus expected) {
        RouteStatus.GroupRouteStatus groupExpected = expected.expectedForGroup(target.getGroup());
        Snapshot snapshot = snapshots.get(target.getProxyId());
        if (heartbeatExpired(target)) {
            return new RouteConvergenceInstance(
                    target.getProxyId(),
                    target.getGroup(),
                    target.getAdvertiseIp(),
                    target.getAdvertisePort(),
                    target.getDataplane(),
                    target.getAdminUrl(),
                    false,
                    0,
                    "",
                    "",
                    0,
                    0,
                    snapshot == null ? null : snapshot.collectedAt(),
                    "UNREACHABLE",
                    "heartbeat expired");
        }
        if (snapshot == null || !snapshot.healthy() || snapshot.routeSnapshot() == null) {
            return new RouteConvergenceInstance(
                    target.getProxyId(),
                    target.getGroup(),
                    target.getAdvertiseIp(),
                    target.getAdvertisePort(),
                    target.getDataplane(),
                    target.getAdminUrl(),
                    false,
                    0,
                    "",
                    "",
                    0,
                    0,
                    snapshot == null ? null : snapshot.collectedAt(),
                    "UNREACHABLE",
                    snapshot == null ? "not collected" : snapshot.error());
        }
        RouteSnapshotObservation route = snapshot.routeSnapshot();
        String status = "CONVERGED";
        String reason = "";
        if (!"success".equalsIgnoreCase(route.lastApplyResult())) {
            status = "STALE";
            reason = "last apply result is not success";
        } else if (route.epoch() < groupExpected.expectedRouteEpoch()) {
            status = "STALE";
            reason = "route epoch is behind expected";
        } else if (route.epoch() == groupExpected.expectedRouteEpoch() && !Objects.equals(route.configHash(), groupExpected.expectedConfigHash())) {
            status = "DRIFT";
            reason = "route epoch matches but config hash differs";
        } else if (route.epoch() > groupExpected.expectedRouteEpoch()) {
            status = "DRIFT";
            reason = "route epoch is ahead of expected";
        }
        return new RouteConvergenceInstance(
                target.getProxyId(),
                route.group(),
                route.advertiseIp(),
                route.advertisePort(),
                target.getDataplane(),
                target.getAdminUrl(),
                true,
                route.epoch(),
                route.configHash(),
                route.lastApplyResult(),
                route.lastApplyTime(),
                route.lastPollTime(),
                route.collectedAt(),
                status,
                reason);
    }

    private void collectSafely(String proxyId) {
        ObservabilityTarget target = targets.get(proxyId);
        if (target == null) {
            return;
        }
        try {
            Snapshot snapshot = collect(target);
            snapshots.put(proxyId, snapshot);
            remember(snapshot);
            publishExternal(snapshot);
        } catch (Exception error) {
            Snapshot previous = snapshots.get(proxyId);
            List<MetricSample> samples = previous == null ? List.of() : previous.samples();
            List<HotKeyObservation> hotKeys = previous == null ? List.of() : previous.hotKeys();
            List<LargeKeyObservation> largeKeys = previous == null ? List.of() : previous.largeKeys();
            List<SlowQueryObservation> slowQueries = previous == null ? List.of() : previous.slowQueries();
            RouteSnapshotObservation routeSnapshot = previous == null ? null : previous.routeSnapshot();
            Snapshot snapshot = new Snapshot(copyTarget(target), false, Instant.now(), error.getMessage(), samples, hotKeys, largeKeys, slowQueries, routeSnapshot);
            snapshots.put(proxyId, snapshot);
            remember(snapshot);
        }
    }

    private Snapshot collect(ObservabilityTarget target) throws IOException, InterruptedException {
        Instant collectedAt = Instant.now();
        String metrics = fetch(target.getAdminUrl(), "/metrics")
                .or(() -> fetch(target.getAdminUrl(), "/actuator/prometheus"))
                .orElseThrow(() -> new IOException("metrics endpoint unavailable"));
        List<MetricSample> samples = parseMetrics(metrics);
        List<Map<String, Object>> hotPayload = readJsonMaps(target.getAdminUrl(), "/debug/hot-keys?limit=100");
        List<Map<String, Object>> largePayload = readJsonMaps(target.getAdminUrl(), "/debug/large-keys?limit=100");
        List<Map<String, Object>> slowPayload = readJsonMaps(target.getAdminUrl(), "/debug/slow-queries?limit=100");
        Map<String, Object> routePayload = readJsonMap(target.getAdminUrl(), "/debug/route-snapshot");
        Map<String, String> resourceAttributes = resourceAttributes(target);
        List<HotKeyObservation> hotKeys = hotPayload.stream()
                .map(item -> new HotKeyObservation(
                        target.getProxyId(),
                        target.getDataplane(),
                        target.getCluster(),
                        string(item, "namespace"),
                        string(item, "command"),
                        string(item, "key"),
                        number(item, "count").longValue(),
                        resourceAttributes,
                        collectedAt,
                        List.of(target.getProxyId())))
                .toList();
        List<LargeKeyObservation> largeKeys = largePayload.stream()
                .map(item -> new LargeKeyObservation(
                        target.getProxyId(),
                        target.getDataplane(),
                        target.getCluster(),
                        string(item, "namespace"),
                        string(item, "command"),
                        string(item, "key"),
                        number(item, "count").longValue(),
                        number(item, "maxRequestBytes").intValue(),
                        number(item, "maxResponseBytes").intValue(),
                        resourceAttributes,
                        collectedAt,
                        List.of(target.getProxyId())))
                .toList();
        List<SlowQueryObservation> slowQueries = slowPayload.stream()
                .map(item -> new SlowQueryObservation(
                        target.getProxyId(),
                        target.getDataplane(),
                        target.getCluster(),
                        string(item, "namespace"),
                        string(item, "command"),
                        string(item, "key"),
                        number(item, "count").longValue(),
                        number(item, "maxEndToEndMillis").longValue(),
                        number(item, "maxBackendMillis").longValue(),
                        resourceAttributes,
                        collectedAt,
                        List.of(target.getProxyId())))
                .toList();
        RouteSnapshotObservation routeSnapshot = new RouteSnapshotObservation(
                target.getProxyId(),
                defaultString(string(routePayload, "group"), target.getGroup()),
                defaultString(string(routePayload, "advertiseIp"), target.getAdvertiseIp()),
                number(routePayload, "advertisePort").intValue() == 0 ? target.getAdvertisePort() : number(routePayload, "advertisePort").intValue(),
                target.getDataplane(),
                target.getAdminUrl(),
                true,
                number(routePayload, "epoch").longValue(),
                string(routePayload, "configHash"),
                string(routePayload, "lastApplyResult"),
                number(routePayload, "lastApplyTime").longValue(),
                number(routePayload, "lastPollTime").longValue(),
                collectedAt,
                "");
        return new Snapshot(copyTarget(target), true, collectedAt, "", samples, hotKeys, largeKeys, slowQueries, routeSnapshot);
    }

    private Optional<String> fetch(String adminUrl, String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(trim(adminUrl) + path))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Optional.of(response.body());
            }
            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private void remember(Snapshot snapshot) {
        CopyOnWriteArrayList<Snapshot> list = history.computeIfAbsent(snapshot.target().getProxyId(), ignored -> new CopyOnWriteArrayList<>());
        list.add(snapshot);
        int max = Math.max(1, properties.getStorage().getMaxSnapshotsPerProxy());
        Instant cutoff = Instant.now().minusSeconds(Math.max(1, properties.getStorage().getRetentionSeconds()));
        while (list.size() > max) {
            list.remove(0);
        }
        list.removeIf(item -> item.collectedAt() != null && item.collectedAt().isBefore(cutoff));
    }

    private void publishExternal(Snapshot snapshot) {
        String type = properties.getStorage().getType() == null ? "memory" : properties.getStorage().getType().toLowerCase(Locale.ROOT);
        try {
            if ("otlp".equals(type)) {
                publishOtlp(snapshot);
            } else if ("influx".equals(type)) {
                publishInflux(snapshot);
            }
        } catch (Exception ignored) {
            // External observability storage must not affect collector freshness.
        }
    }

    private void publishOtlp(Snapshot snapshot) throws IOException, InterruptedException {
        String endpoint = trim(properties.getStorage().getOtlp().getEndpoint()) + "/v1/logs";
        List<Map<String, Object>> records = new ArrayList<>();
        long timeUnixNano = (snapshot.collectedAt() == null ? Instant.now() : snapshot.collectedAt()).toEpochMilli() * 1_000_000L;
        for (MetricSample sample : snapshot.samples()) {
            records.add(otlpLogRecord(timeUnixNano, "redis.proxy.metric.sample", Map.of(
                    "metric.name", sample.name(),
                    "metric.labels", sample.labels().toString(),
                    "metric.value", Double.toString(sample.value()))));
        }
        for (HotKeyObservation item : snapshot.hotKeys()) {
            records.add(otlpLogRecord(timeUnixNano, "redis.proxy.hot_key", detailAttributes(item.namespace(), item.command(), item.key(), item.count())));
        }
        for (LargeKeyObservation item : snapshot.largeKeys()) {
            Map<String, String> attrs = detailAttributes(item.namespace(), item.command(), item.key(), item.count());
            attrs.put("max_request_bytes", Integer.toString(item.maxRequestBytes()));
            attrs.put("max_response_bytes", Integer.toString(item.maxResponseBytes()));
            records.add(otlpLogRecord(timeUnixNano, "redis.proxy.large_key", attrs));
        }
        for (SlowQueryObservation item : snapshot.slowQueries()) {
            Map<String, String> attrs = detailAttributes(item.namespace(), item.command(), item.key(), item.count());
            attrs.put("max_end_to_end_millis", Long.toString(item.maxEndToEndMillis()));
            attrs.put("max_backend_millis", Long.toString(item.maxBackendMillis()));
            records.add(otlpLogRecord(timeUnixNano, "redis.proxy.slow_query", attrs));
        }
        Map<String, Object> payload = Map.of("resourceLogs", List.of(Map.of(
                "resource", Map.of("attributes", otlpAttributes(resourceAttributes(snapshot.target()))),
                "scopeLogs", List.of(Map.of(
                        "scope", Map.of("name", "redis-proxy-control-plane"),
                        "logRecords", records)))));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        properties.getStorage().getOtlp().getHeaders().forEach(builder::header);
        httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
    }

    private void publishInflux(Snapshot snapshot) throws IOException, InterruptedException {
        String base = trim(properties.getStorage().getInflux().getUrl());
        String org = encode(properties.getStorage().getInflux().getOrg());
        String bucket = encode(properties.getStorage().getInflux().getBucket());
        String endpoint = base + "/api/v2/write?org=" + org + "&bucket=" + bucket + "&precision=s";
        long timestamp = snapshot.collectedAt() == null ? Instant.now().getEpochSecond() : snapshot.collectedAt().getEpochSecond();
        StringBuilder body = new StringBuilder();
        for (MetricSample sample : snapshot.samples()) {
            body.append("redis_proxy_metric,proxyId=").append(escapeTag(snapshot.target().getProxyId()))
                    .append(",metric=").append(escapeTag(sample.name()))
                    .append(" value=").append(sample.value())
                    .append(' ').append(timestamp).append('\n');
        }
        for (SlowQueryObservation item : snapshot.slowQueries()) {
            body.append("redis_proxy_slow_query,proxyId=").append(escapeTag(snapshot.target().getProxyId()))
                    .append(",namespace=").append(escapeTag(item.namespace()))
                    .append(",command=").append(escapeTag(item.command()))
                    .append(" count=").append(item.count())
                    .append(",maxEndToEndMillis=").append(item.maxEndToEndMillis())
                    .append(",maxBackendMillis=").append(item.maxBackendMillis())
                    .append(",key=\"").append(escapeField(item.key())).append("\"")
                    .append(' ').append(timestamp).append('\n');
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!blank(properties.getStorage().getInflux().getToken())) {
            builder.header("Authorization", "Token " + properties.getStorage().getInflux().getToken());
        }
        httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String adminUrl, String path) throws IOException {
        String body = fetch(adminUrl, path).orElseThrow(() -> new IOException(path + " unavailable"));
        Object parsed = objectMapper.readValue(body, Object.class);
        if (parsed instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IOException(path + " returned non-object payload");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonMaps(String adminUrl, String path) throws IOException {
        String body = fetch(adminUrl, path).orElse("[]");
        Object parsed = objectMapper.readValue(body, Object.class);
        if (!(parsed instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static ObservabilityTarget normalize(ObservabilityTarget input) {
        if (input == null) {
            throw new IllegalArgumentException("observability target is required");
        }
        if (blank(input.getProxyId())) {
            throw new IllegalArgumentException("proxyId is required");
        }
        if (blank(input.getAdminUrl())) {
            throw new IllegalArgumentException("adminUrl is required");
        }
        if (blank(input.getDataplane())) {
            throw new IllegalArgumentException("dataplane is required");
        }
        ObservabilityTarget target = copyTarget(input);
        target.setProxyId(input.getProxyId().trim());
        target.setGroup(blank(input.getGroup()) ? "default" : input.getGroup().trim());
        target.setAdvertiseIp(input.getAdvertiseIp() == null ? "" : input.getAdvertiseIp().trim());
        target.setAdvertisePort(Math.max(0, input.getAdvertisePort()));
        target.setAdminUrl(trim(input.getAdminUrl()));
        target.setDataplane(input.getDataplane().trim());
        target.setCluster(input.getCluster() == null ? "" : input.getCluster().trim());
        target.setServiceNamespace(blank(input.getServiceNamespace()) ? "redis-proxy" : input.getServiceNamespace().trim());
        target.setServiceName(blank(input.getServiceName()) ? "redis-proxy-dataplane" : input.getServiceName().trim());
        target.setServiceInstanceId(blank(input.getServiceInstanceId()) ? target.getProxyId() : input.getServiceInstanceId().trim());
        target.setDeploymentEnvironmentName(input.getDeploymentEnvironmentName() == null ? "" : input.getDeploymentEnvironmentName().trim());
        target.setRegistrationSource(blank(input.getRegistrationSource()) ? "manual" : input.getRegistrationSource().trim());
        target.setLastHeartbeatAt(input.getLastHeartbeatAt() == null ? "" : input.getLastHeartbeatAt().trim());
        int ttl = input.getHeartbeatTtlSeconds() <= 0 ? DEFAULT_HEARTBEAT_TTL_SECONDS : input.getHeartbeatTtlSeconds();
        target.setHeartbeatTtlSeconds(Math.min(Math.max(ttl, 1), 3600));
        int interval = input.getPollIntervalSeconds() <= 0 ? DEFAULT_POLL_INTERVAL_SECONDS : input.getPollIntervalSeconds();
        target.setPollIntervalSeconds(Math.min(Math.max(interval, MIN_POLL_INTERVAL_SECONDS), MAX_POLL_INTERVAL_SECONDS));
        return target;
    }

    private static ObservabilityTarget copyTarget(ObservabilityTarget source) {
        ObservabilityTarget target = new ObservabilityTarget();
        target.setProxyId(source.getProxyId());
        target.setGroup(source.getGroup());
        target.setAdvertiseIp(source.getAdvertiseIp());
        target.setAdvertisePort(source.getAdvertisePort());
        target.setAdminUrl(source.getAdminUrl());
        target.setDataplane(source.getDataplane());
        target.setCluster(source.getCluster());
        target.setPollIntervalSeconds(source.getPollIntervalSeconds());
        target.setServiceNamespace(source.getServiceNamespace());
        target.setServiceName(source.getServiceName());
        target.setServiceInstanceId(source.getServiceInstanceId());
        target.setDeploymentEnvironmentName(source.getDeploymentEnvironmentName());
        target.setRegistrationSource(source.getRegistrationSource());
        target.setLastHeartbeatAt(source.getLastHeartbeatAt());
        target.setHeartbeatTtlSeconds(source.getHeartbeatTtlSeconds());
        return target;
    }

    private static boolean heartbeatExpired(ObservabilityTarget target) {
        if (!"dataplane".equalsIgnoreCase(target.getRegistrationSource())) {
            return false;
        }
        Optional<Instant> heartbeat = parseInstant(target.getLastHeartbeatAt());
        if (heartbeat.isEmpty()) {
            return false;
        }
        int ttl = target.getHeartbeatTtlSeconds() <= 0 ? DEFAULT_HEARTBEAT_TTL_SECONDS : target.getHeartbeatTtlSeconds();
        return heartbeat.get().plusSeconds(ttl).isBefore(Instant.now());
    }

    private static Optional<Instant> parseInstant(String value) {
        if (blank(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Map<String, String> resourceAttributes(ObservabilityTarget target) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("service.namespace", blank(target.getServiceNamespace()) ? "redis-proxy" : target.getServiceNamespace());
        attributes.put("service.name", blank(target.getServiceName()) ? "redis-proxy-dataplane" : target.getServiceName());
        attributes.put("service.instance.id", blank(target.getServiceInstanceId()) ? target.getProxyId() : target.getServiceInstanceId());
        if (!blank(target.getDeploymentEnvironmentName())) {
            attributes.put("deployment.environment.name", target.getDeploymentEnvironmentName());
        }
        attributes.put("redis.proxy.dataplane", target.getDataplane());
        attributes.put("redis.proxy.group", blank(target.getGroup()) ? "default" : target.getGroup());
        if (!blank(target.getCluster())) {
            attributes.put("redis.proxy.cluster", target.getCluster());
        }
        if (!blank(target.getAdvertiseIp())) {
            attributes.put("redis.proxy.advertise.ip", target.getAdvertiseIp());
        }
        if (target.getAdvertisePort() > 0) {
            attributes.put("redis.proxy.advertise.port", Integer.toString(target.getAdvertisePort()));
        }
        return Map.copyOf(attributes);
    }

    private static List<MetricSample> parseMetrics(String text) {
        List<MetricSample> samples = new ArrayList<>();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int space = line.lastIndexOf(' ');
            if (space <= 0) {
                continue;
            }
            String left = line.substring(0, space);
            double value;
            try {
                value = Double.parseDouble(line.substring(space + 1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            String name = left;
            Map<String, String> labels = Map.of();
            int brace = left.indexOf('{');
            if (brace >= 0 && left.endsWith("}")) {
                name = left.substring(0, brace);
                labels = parseLabels(left.substring(brace + 1, left.length() - 1));
            }
            samples.add(new MetricSample(name, labels, value));
        }
        return samples;
    }

    private static Map<String, String> parseLabels(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> labels = new HashMap<>();
        StringBuilder part = new StringBuilder();
        boolean inQuote = false;
        boolean escaped = false;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                part.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                part.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                inQuote = !inQuote;
                part.append(c);
                continue;
            }
            if (c == ',' && !inQuote) {
                parts.add(part.toString());
                part.setLength(0);
                continue;
            }
            part.append(c);
        }
        if (!part.isEmpty()) {
            parts.add(part.toString());
        }
        for (String item : parts) {
            int equals = item.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = item.substring(0, equals).trim();
            String value = item.substring(equals + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            labels.put(key, value.replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return labels;
    }

    private static double sum(List<Snapshot> snapshots, String metric) {
        return snapshots.stream()
                .flatMap(snapshot -> snapshot.samples().stream())
                .filter(sample -> sample.name().equals(metric))
                .mapToDouble(MetricSample::value)
                .sum();
    }

    private static double gaugeSum(List<Snapshot> snapshots, String metric) {
        return snapshots.stream()
                .mapToDouble(snapshot -> snapshot.samples().stream()
                        .filter(sample -> sample.name().equals(metric))
                        .reduce((first, second) -> second)
                        .map(MetricSample::value)
                        .orElse(0.0))
                .sum();
    }

    private static boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || Objects.equals(expected, actual);
    }

    private static int boundLimit(int limit) {
        if (limit <= 0) {
            return 100;
        }
        return Math.min(limit, 1000);
    }

    private static String trim(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String string(Map<String, Object> item, String field) {
        Object value = item.get(field);
        return value == null ? "" : value.toString();
    }

    private static String defaultString(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private static Number number(Map<String, Object> item, String field) {
        Object value = item.get(field);
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static void mergeHotKey(Map<String, HotKeyObservation> items, HotKeyObservation item) {
        String key = aggregateKey(item.namespace(), item.command(), item.key());
        HotKeyObservation previous = items.get(key);
        if (previous == null) {
            items.put(key, item);
            return;
        }
        items.put(key, new HotKeyObservation(
                "*",
                previous.dataplane(),
                previous.cluster(),
                item.namespace(),
                item.command(),
                item.key(),
                previous.count() + item.count(),
                previous.resourceAttributes(),
                latest(previous.collectedAt(), item.collectedAt()),
                mergeProxyIds(previous.proxyIds(), item.proxyIds())));
    }

    private static void mergeLargeKey(Map<String, LargeKeyObservation> items, LargeKeyObservation item) {
        String key = aggregateKey(item.namespace(), item.command(), item.key());
        LargeKeyObservation previous = items.get(key);
        if (previous == null) {
            items.put(key, item);
            return;
        }
        items.put(key, new LargeKeyObservation(
                "*",
                previous.dataplane(),
                previous.cluster(),
                item.namespace(),
                item.command(),
                item.key(),
                previous.count() + item.count(),
                Math.max(previous.maxRequestBytes(), item.maxRequestBytes()),
                Math.max(previous.maxResponseBytes(), item.maxResponseBytes()),
                previous.resourceAttributes(),
                latest(previous.collectedAt(), item.collectedAt()),
                mergeProxyIds(previous.proxyIds(), item.proxyIds())));
    }

    private static void mergeSlowQuery(Map<String, SlowQueryObservation> items, SlowQueryObservation item) {
        String key = aggregateKey(item.namespace(), item.command(), item.key());
        SlowQueryObservation previous = items.get(key);
        if (previous == null) {
            items.put(key, item);
            return;
        }
        items.put(key, new SlowQueryObservation(
                "*",
                previous.dataplane(),
                previous.cluster(),
                item.namespace(),
                item.command(),
                item.key(),
                previous.count() + item.count(),
                Math.max(previous.maxEndToEndMillis(), item.maxEndToEndMillis()),
                Math.max(previous.maxBackendMillis(), item.maxBackendMillis()),
                previous.resourceAttributes(),
                latest(previous.collectedAt(), item.collectedAt()),
                mergeProxyIds(previous.proxyIds(), item.proxyIds())));
    }

    private static String aggregateKey(String namespace, String command, String key) {
        return namespace + "\u0000" + command + "\u0000" + key;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    private static List<String> mergeProxyIds(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        if (a != null) {
            out.addAll(a);
        }
        if (b != null) {
            for (String item : b) {
                if (!out.contains(item)) {
                    out.add(item);
                }
            }
        }
        return out.stream().sorted().toList();
    }

    private static Instant bucketTime(Instant timestamp, int stepSeconds) {
        long epoch = timestamp.getEpochSecond();
        return Instant.ofEpochSecond((epoch / stepSeconds) * stepSeconds);
    }

    private static void writeGauge(StringBuilder out, String name, double value) {
        out.append("# TYPE ").append(name).append(" gauge\n");
        out.append(name).append(' ').append(value).append('\n');
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String escapeTag(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=");
    }

    private static String escapeField(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<Map<String, Object>> otlpAttributes(Map<String, String> attributes) {
        return attributes.entrySet().stream()
                .map(entry -> Map.of("key", entry.getKey(), "value", Map.of("stringValue", entry.getValue())))
                .toList();
    }

    private static Map<String, Object> otlpLogRecord(long timeUnixNano, String body, Map<String, String> attributes) {
        return Map.of(
                "timeUnixNano", Long.toString(timeUnixNano),
                "severityText", "INFO",
                "body", Map.of("stringValue", body),
                "attributes", otlpAttributes(attributes));
    }

    private static Map<String, String> detailAttributes(String namespace, String command, String key, long count) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("namespace", namespace);
        attrs.put("command", command);
        attrs.put("key", key);
        attrs.put("count", Long.toString(count));
        return attrs;
    }

    private record Snapshot(
            ObservabilityTarget target,
            boolean healthy,
            Instant collectedAt,
            String error,
            List<MetricSample> samples,
            List<HotKeyObservation> hotKeys,
            List<LargeKeyObservation> largeKeys,
            List<SlowQueryObservation> slowQueries,
            RouteSnapshotObservation routeSnapshot) {
        private static Snapshot empty(ObservabilityTarget target) {
            return new Snapshot(copyTarget(target), false, null, "not collected", List.of(), List.of(), List.of(), List.of(), null);
        }
    }

    private record MetricSample(String name, Map<String, String> labels, double value) {}

    private static final class MutableHistoryPoint {
        private final Instant timestamp;
        private final String metric;
        private final Map<String, String> labels;
        private final Map<String, String> resourceAttributes;
        private final boolean counter;
        private double value;

        private MutableHistoryPoint(Instant timestamp, String metric, Map<String, String> labels, Map<String, String> resourceAttributes, boolean counter) {
            this.timestamp = timestamp;
            this.metric = metric;
            this.labels = labels;
            this.resourceAttributes = resourceAttributes;
            this.counter = counter;
        }

        private void add(double next) {
            if (counter) {
                value += next;
            } else {
                value = next;
            }
        }

        private HistoryPoint toPoint() {
            return new HistoryPoint(timestamp, metric, value, labels, resourceAttributes);
        }
    }
}
