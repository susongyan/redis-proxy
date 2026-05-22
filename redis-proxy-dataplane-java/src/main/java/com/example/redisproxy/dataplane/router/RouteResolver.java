package com.example.redisproxy.dataplane.router;

import com.example.redisproxy.dataplane.backend.BackendPool;
import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.governance.GovernancePolicy;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import com.example.redisproxy.dataplane.protocol.RespValue;
import com.example.redisproxy.dataplane.protocol.RespValueParser;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class RouteResolver {
    private static final int SLOTS = 16384;
    private static final byte[] CLUSTER_SLOTS =
            "*2\r\n$7\r\nCLUSTER\r\n$5\r\nSLOTS\r\n".getBytes(StandardCharsets.US_ASCII);

    private final AtomicReference<Snapshot> snapshot;
    private final AtomicInteger slotCoverage = new AtomicInteger();
    private final AtomicLong lastRefreshTimestampSeconds = new AtomicLong();
    private final Map<String, String[]> slotNodes = new ConcurrentHashMap<>();

    public RouteResolver(ProxyProperties properties, MeterRegistry registry) {
        properties.validate();
        this.snapshot = new AtomicReference<>(new Snapshot(properties, clusters(properties)));
        for (String clusterName : snapshot.get().clusters().keySet()) {
            slotNodes.put(clusterName, new String[SLOTS]);
        }
        slotCoverage.set(clusterSlotCoverage(properties.getRouting().getDefaultCluster()));
        registry.gauge("redis.proxy.cluster.slot.coverage", slotCoverage);
        registry.gauge("redis.proxy.cluster.slot.last.refresh.timestamp.seconds", lastRefreshTimestampSeconds);
        registry.gauge("redis.proxy.route.epoch", this, RouteResolver::currentEpoch);
    }

    public String route(RespRequest request) {
        return routeDecision(request).address();
    }

    public RouteDecision routeDecision(RespRequest request) {
        Snapshot current = snapshot.get();
        SelectedCluster selected = selectCluster(current, request);
        ProxyProperties.Cluster cluster = current.clusters().get(selected.cluster());
        if (cluster == null) {
            throw new IllegalArgumentException("route cluster not found: " + selected.cluster());
        }
        List<String> nodes = cluster.getNodes();
        if (!"cluster".equals(current.properties().getMode()) || nodes.size() == 1 || request.args().size() < 2) {
            return new RouteDecision(nodes.getFirst(), selected.cluster(), selected.rule(), currentEpoch());
        }
        int slot = RedisSlot.slot(request.args().get(1));
        String cached = slotNodes.get(selected.cluster())[slot];
        if (cached != null && !cached.isBlank()) {
            return new RouteDecision(cached, selected.cluster(), selected.rule(), currentEpoch());
        }
        return new RouteDecision(nodes.get(slot % nodes.size()), selected.cluster(), selected.rule(), currentEpoch());
    }

    public void refreshSlots(BackendPool backendPool, Duration timeout) throws Exception {
        Snapshot current = snapshot.get();
        if (!"cluster".equals(current.properties().getMode())) {
            return;
        }
        Exception refreshError = null;
        for (String clusterName : current.clusters().keySet()) {
            ProxyProperties.Cluster cluster = current.clusters().get(clusterName);
            Exception lastError = null;
            boolean refreshed = false;
            for (String seed : cluster.getNodes()) {
                ByteBuf request = Unpooled.wrappedBuffer(CLUSTER_SLOTS);
                ByteBuf response = null;
                try {
                    response = backendPool.doRequest(seed, request).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    applyClusterSlots(clusterName, response);
                    backendPool.ensureAll(snapshotNodes(clusterName));
                    refreshed = true;
                    break;
                } catch (Exception e) {
                    lastError = e;
                } finally {
                    request.release();
                    if (response != null) {
                        response.release();
                    }
                }
            }
            if (!refreshed) {
                refreshError = lastError == null ? new IllegalStateException("cluster " + clusterName + " slot refresh failed") : lastError;
            }
        }
        if (refreshError != null) {
            throw refreshError;
        }
    }

    public void updateMoved(ByteBuf response, BackendPool backendPool) {
        Redirection redirection = parseRedirection(response, "MOVED");
        if (redirection == null) {
            return;
        }
        MovedTarget target = normalizeMovedAddr(redirection.address());
        String[] current = slotNodes.get(target.clusterName());
        String[] next = Arrays.copyOf(current, current.length);
        next[redirection.slot()] = target.address();
        slotNodes.put(target.clusterName(), next);
        slotCoverage.set(slotCoverage());
        if (backendPool != null) {
            backendPool.ensure(target.address());
        }
    }

    public String askTarget(ByteBuf response, String clusterName, BackendPool backendPool) {
        Redirection redirection = parseRedirection(response, "ASK");
        if (redirection == null) {
            throw new IllegalArgumentException("invalid ASK response");
        }
        String address = normalizeAddr(snapshot.get(), clusterName, redirection.address());
        if (backendPool != null) {
            backendPool.ensure(address);
        }
        return address;
    }

    private Redirection parseRedirection(ByteBuf response, String kind) {
        String text = response.toString(response.readerIndex(), response.readableBytes(), StandardCharsets.US_ASCII);
        if (!text.startsWith("-" + kind + " ")) {
            return null;
        }
        String[] fields = text.split("\\s+");
        if (fields.length < 3) {
            return null;
        }
        int slot;
        try {
            slot = Integer.parseInt(fields[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (slot < 0 || slot >= SLOTS) {
            return null;
        }
        return new Redirection(slot, fields[2]);
    }

    public int slotCoverage() {
        return clusterSlotCoverage(snapshot.get().properties().getRouting().getDefaultCluster());
    }

    public int clusterSlotCoverage(String clusterName) {
        String[] nodes = slotNodes.get(clusterName);
        return nodes == null ? 0 : countCovered(nodes);
    }

    public List<String> slotOwners() {
        Set<String> seen = new HashSet<>();
        List<String> owners = new ArrayList<>();
        for (String clusterName : routeClusters()) {
            for (String owner : clusterSlotOwners(clusterName)) {
                if (seen.add(owner)) {
                    owners.add(owner);
                }
            }
        }
        return owners;
    }

    public List<String> clusterSlotOwners(String clusterName) {
        String[] nodes = slotNodes.get(clusterName);
        if (nodes == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        for (String node : nodes) {
            if (node != null && !node.isBlank()) {
                seen.add(node);
            }
        }
        return List.copyOf(seen);
    }

    public List<String> defaultNodes() {
        return clusterNodes(snapshot.get().properties().getRouting().getDefaultCluster());
    }

    public List<String> routeClusters() {
        Snapshot current = snapshot.get();
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        result.add(current.properties().getRouting().getDefaultCluster());
        seen.add(current.properties().getRouting().getDefaultCluster());
        for (ProxyProperties.RouteRule rule : current.properties().getRouting().getRules()) {
            if (seen.add(rule.getCluster())) {
                result.add(rule.getCluster());
            }
        }
        return result;
    }

    public List<String> clusterNodes(String clusterName) {
        ProxyProperties.Cluster cluster = snapshot.get().clusters().get(clusterName);
        return cluster == null ? List.of() : List.copyOf(cluster.getNodes());
    }

    public long currentEpoch() {
        return snapshot.get().properties().getRouting().getRouteEpoch();
    }

    public SnapshotInfo snapshotInfo() {
        Snapshot current = snapshot.get();
        return new SnapshotInfo(
                current.properties().getRouting().getRouteEpoch(),
                current.properties().getMode(),
                current.properties().getRouting().getDefaultCluster(),
                routeClusters(),
                List.copyOf(current.properties().getRouting().getRules()),
                GovernancePolicy.summary(current.properties().getGovernance()));
    }

    public ProxyProperties.Governance governance() {
        return snapshot.get().properties().getGovernance();
    }

    public ProxyProperties.Limits limits() {
        return snapshot.get().properties().getLimits();
    }

    public ProxyProperties.Analysis analysis() {
        return snapshot.get().properties().getAnalysis();
    }

    public ApplyResult applyConfig(ProxyProperties next, BackendPool backendPool) {
        next.validate();
        Snapshot current = snapshot.get();
        if (!next.getMode().equals(current.properties().getMode())) {
            return new ApplyResult("runtime_shape", false, "mode changes are not hot reloadable");
        }
        if (next.getRouting().getRouteEpoch() <= current.properties().getRouting().getRouteEpoch()) {
            return new ApplyResult("stale_epoch", false, "routeEpoch must be greater than current");
        }
        for (ProxyProperties.Cluster cluster : next.getBackends().getClusters()) {
            backendPool.ensureAll(cluster.getNodes());
            slotNodes.computeIfAbsent(cluster.getName(), ignored -> new String[SLOTS]);
        }
        snapshot.set(new Snapshot(next, clusters(next)));
        slotCoverage.set(slotCoverage());
        return new ApplyResult("success", true, null);
    }

    String normalizeAddr(String addr) {
        Snapshot current = snapshot.get();
        return normalizeAddr(current, current.properties().getRouting().getDefaultCluster(), addr);
    }

    String normalizeAddr(String clusterName, String addr) {
        return normalizeAddr(snapshot.get(), clusterName, addr);
    }

    private String normalizeAddr(Snapshot current, String clusterName, String addr) {
        HostPort target = HostPort.parse(addr);
        ProxyProperties.Cluster cluster = current.clusters().get(clusterName);
        if (cluster == null) {
            return target.host() + ":" + target.port();
        }
        for (String node : cluster.getNodes()) {
            HostPort configured = HostPort.parse(node);
            if (configured.port() == target.port()) {
                return node;
            }
        }
        return target.host() + ":" + target.port();
    }

    void applyClusterSlots(ByteBuf raw) {
        applyClusterSlots(snapshot.get().properties().getRouting().getDefaultCluster(), raw);
    }

    void applyClusterSlots(String clusterName, ByteBuf raw) {
        RespValue value = RespValueParser.parse(raw);
        if (value.kind() != RespValue.Kind.ARRAY) {
            throw new IllegalArgumentException("CLUSTER SLOTS returned " + value.kind());
        }
        String[] next = new String[SLOTS];
        for (RespValue range : value.array()) {
            if (range.kind() != RespValue.Kind.ARRAY || range.array().size() < 3) {
                continue;
            }
            int start = (int) range.array().get(0).integer();
            int end = (int) range.array().get(1).integer();
            RespValue master = range.array().get(2);
            if (master.kind() != RespValue.Kind.ARRAY || master.array().size() < 2) {
                continue;
            }
            String host = new String(master.array().get(0).bytes(), StandardCharsets.UTF_8);
            int port = (int) master.array().get(1).integer();
            String addr = normalizeAddr(snapshot.get(), clusterName, host + ":" + port);
            start = Math.max(0, start);
            end = Math.min(SLOTS - 1, end);
            for (int slot = start; slot <= end; slot++) {
                next[slot] = addr;
            }
        }
        slotNodes.put(clusterName, next);
        slotCoverage.set(slotCoverage());
        lastRefreshTimestampSeconds.set(System.currentTimeMillis() / 1000);
    }

    private List<String> snapshotNodes(String clusterName) {
        Map<String, Boolean> seen = new HashMap<>();
        for (String node : slotNodes.get(clusterName)) {
            if (node != null && !node.isBlank()) {
                seen.put(node, true);
            }
        }
        return List.copyOf(seen.keySet());
    }

    private SelectedCluster selectCluster(Snapshot current, RespRequest request) {
        if (request.args().size() < 2) {
            return new SelectedCluster(current.properties().getRouting().getDefaultCluster(), "default");
        }
        byte[] rawKey = request.args().get(1);
        String key = new String(rawKey, StandardCharsets.UTF_8);
        String hashTag = new String(hashTag(rawKey), StandardCharsets.UTF_8);
        for (ProxyProperties.RouteRule rule : current.properties().getRouting().getRules()) {
            if (rule.getTrafficPercent() <= 0) {
                continue;
            }
            if (rule.getKeyPrefix() != null && !rule.getKeyPrefix().isBlank() && !key.startsWith(rule.getKeyPrefix())) {
                continue;
            }
            if (rule.getHashTag() != null && !rule.getHashTag().isBlank() && !hashTag.equals(rule.getHashTag())) {
                continue;
            }
            if (rule.getTrafficPercent() >= 100 || RedisSlot.slot(rawKey) % 100 < rule.getTrafficPercent()) {
                return new SelectedCluster(rule.getCluster(), rule.getName());
            }
        }
        return new SelectedCluster(current.properties().getRouting().getDefaultCluster(), "default");
    }

    private MovedTarget normalizeMovedAddr(String addr) {
        Snapshot current = snapshot.get();
        for (String clusterName : current.clusters().keySet()) {
            String normalized = normalizeAddr(current, clusterName, addr);
            if (current.clusters().get(clusterName).getNodes().contains(normalized)) {
                return new MovedTarget(clusterName, normalized);
            }
        }
        String defaultCluster = current.properties().getRouting().getDefaultCluster();
        return new MovedTarget(defaultCluster, normalizeAddr(current, defaultCluster, addr));
    }

    private static Map<String, ProxyProperties.Cluster> clusters(ProxyProperties properties) {
        Map<String, ProxyProperties.Cluster> result = new HashMap<>();
        for (ProxyProperties.Cluster cluster : properties.getBackends().getClusters()) {
            result.put(cluster.getName(), cluster);
        }
        return Map.copyOf(result);
    }

    private static int countCovered(String[] nodes) {
        int covered = 0;
        for (String node : nodes) {
            if (node != null && !node.isBlank()) {
                covered++;
            }
        }
        return covered;
    }

    private static byte[] hashTag(byte[] key) {
        int start = -1;
        for (int i = 0; i < key.length; i++) {
            if (key[i] == '{') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return key;
        }
        for (int i = start + 1; i < key.length; i++) {
            if (key[i] == '}') {
                if (i == start + 1) {
                    return key;
                }
                byte[] tag = new byte[i - start - 1];
                System.arraycopy(key, start + 1, tag, 0, tag.length);
                return tag;
            }
        }
        return key;
    }

    public record RouteDecision(String address, String cluster, String rule, long epoch) {}
    public record SnapshotInfo(long epoch, String mode, String defaultCluster, List<String> routeClusters, List<ProxyProperties.RouteRule> rules, Map<String, Object> governance) {}
    public record ApplyResult(String result, boolean applied, String error) {}
    private record Snapshot(ProxyProperties properties, Map<String, ProxyProperties.Cluster> clusters) {}
    private record SelectedCluster(String cluster, String rule) {}
    private record MovedTarget(String clusterName, String address) {}
    private record Redirection(int slot, String address) {}

    private record HostPort(String host, int port) {
        static HostPort parse(String value) {
            int index = value.lastIndexOf(':');
            if (index <= 0 || index == value.length() - 1) {
                throw new IllegalArgumentException("invalid backend address: " + value);
            }
            return new HostPort(value.substring(0, index), Integer.parseInt(value.substring(index + 1)));
        }
    }
}
