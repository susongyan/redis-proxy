package com.example.redisproxy.dataplane.router;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import com.example.redisproxy.dataplane.backend.BackendPool;
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
import org.springframework.stereotype.Component;

@Component
public class RouteResolver {
    private static final int SLOTS = 16384;
    private static final byte[] CLUSTER_SLOTS =
            "*2\r\n$7\r\nCLUSTER\r\n$5\r\nSLOTS\r\n".getBytes(StandardCharsets.US_ASCII);

    private final ProxyProperties properties;
    private final Map<String, ProxyProperties.Cluster> clusters = new HashMap<>();
    private final AtomicInteger slotCoverage = new AtomicInteger();
    private final AtomicLong lastRefreshTimestampSeconds = new AtomicLong();
    private final Map<String, String[]> slotNodes = new ConcurrentHashMap<>();

    public RouteResolver(ProxyProperties properties, MeterRegistry registry) {
        properties.validate();
        this.properties = properties;
        for (ProxyProperties.Cluster cluster : properties.getBackends().getClusters()) {
            clusters.put(cluster.getName(), cluster);
            slotNodes.put(cluster.getName(), new String[SLOTS]);
        }
        registry.gauge("redis.proxy.cluster.slot.coverage", slotCoverage);
        registry.gauge("redis.proxy.cluster.slot.last.refresh.timestamp.seconds", lastRefreshTimestampSeconds);
        registry.gauge("redis.proxy.route.epoch", properties.getRouting(), routing -> routing.getRouteEpoch());
    }

    public String route(RespRequest request) {
        String clusterName = selectCluster(request);
        ProxyProperties.Cluster cluster = clusters.get(clusterName);
        if (cluster == null) {
            throw new IllegalArgumentException("route cluster not found: " + clusterName);
        }
        List<String> nodes = cluster.getNodes();
        if (!"cluster".equals(properties.getMode()) || nodes.size() == 1 || request.args().size() < 2) {
            return nodes.getFirst();
        }
        int slot = RedisSlot.slot(request.args().get(1));
        String cached = slotNodes.get(clusterName)[slot];
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        return nodes.get(slot % nodes.size());
    }

    public void refreshSlots(BackendPool backendPool, Duration timeout) throws Exception {
        if (!"cluster".equals(properties.getMode())) {
            return;
        }
        Exception refreshError = null;
        for (String clusterName : clusters.keySet()) {
            ProxyProperties.Cluster cluster = clusters.get(clusterName);
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
            if (!refreshed && lastError == null) {
                lastError = new IllegalStateException("cluster " + clusterName + " slot refresh failed");
            }
            if (!refreshed) {
                refreshError = lastError;
            }
        }
        if (refreshError != null) {
            throw refreshError;
        }
    }

    public void updateMoved(ByteBuf response, BackendPool backendPool) {
        String text = response.toString(response.readerIndex(), response.readableBytes(), StandardCharsets.US_ASCII);
        if (!text.startsWith("-MOVED ")) {
            return;
        }
        String[] fields = text.split("\\s+");
        if (fields.length < 3) {
            return;
        }
        int slot;
        try {
            slot = Integer.parseInt(fields[1]);
        } catch (NumberFormatException e) {
            return;
        }
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        MovedTarget target = normalizeMovedAddr(fields[2]);
        String[] current = slotNodes.get(target.clusterName());
        String[] next = Arrays.copyOf(current, current.length);
        next[slot] = target.address();
        slotNodes.put(target.clusterName(), next);
        slotCoverage.set(clusterSlotCoverage(properties.getRouting().getDefaultCluster()));
        if (backendPool != null) {
            backendPool.ensure(target.address());
        }
    }

    public int slotCoverage() {
        return clusterSlotCoverage(properties.getRouting().getDefaultCluster());
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
        return clusterNodes(properties.getRouting().getDefaultCluster());
    }

    public List<String> routeClusters() {
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        result.add(properties.getRouting().getDefaultCluster());
        seen.add(properties.getRouting().getDefaultCluster());
        for (ProxyProperties.RouteRule rule : properties.getRouting().getRules()) {
            if (seen.add(rule.getCluster())) {
                result.add(rule.getCluster());
            }
        }
        return result;
    }

    public List<String> clusterNodes(String clusterName) {
        ProxyProperties.Cluster cluster = clusters.get(clusterName);
        return cluster == null ? List.of() : List.copyOf(cluster.getNodes());
    }

    String normalizeAddr(String addr) {
        return normalizeAddr(properties.getRouting().getDefaultCluster(), addr);
    }

    String normalizeAddr(String clusterName, String addr) {
        HostPort target = HostPort.parse(addr);
        ProxyProperties.Cluster cluster = clusters.get(clusterName);
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
        applyClusterSlots(properties.getRouting().getDefaultCluster(), raw);
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
            String addr = normalizeAddr(clusterName, host + ":" + port);
            start = Math.max(0, start);
            end = Math.min(SLOTS - 1, end);
            for (int slot = start; slot <= end; slot++) {
                next[slot] = addr;
            }
        }
        slotNodes.put(clusterName, next);
        slotCoverage.set(clusterSlotCoverage(properties.getRouting().getDefaultCluster()));
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

    private static int countCovered(String[] nodes) {
        int covered = 0;
        for (String node : nodes) {
            if (node != null && !node.isBlank()) {
                covered++;
            }
        }
        return covered;
    }

    private String selectCluster(RespRequest request) {
        if (request.args().size() < 2) {
            return properties.getRouting().getDefaultCluster();
        }
        byte[] rawKey = request.args().get(1);
        String key = new String(rawKey, StandardCharsets.UTF_8);
        String hashTag = new String(hashTag(rawKey), StandardCharsets.UTF_8);
        for (ProxyProperties.RouteRule rule : properties.getRouting().getRules()) {
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
                return rule.getCluster();
            }
        }
        return properties.getRouting().getDefaultCluster();
    }

    private MovedTarget normalizeMovedAddr(String addr) {
        for (String clusterName : clusters.keySet()) {
            String normalized = normalizeAddr(clusterName, addr);
            if (clusters.get(clusterName).getNodes().contains(normalized)) {
                return new MovedTarget(clusterName, normalized);
            }
        }
        String defaultCluster = properties.getRouting().getDefaultCluster();
        return new MovedTarget(defaultCluster, normalizeAddr(defaultCluster, addr));
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

    private record MovedTarget(String clusterName, String address) {}

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
