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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ProxyProperties.Cluster defaultCluster;
    private final AtomicInteger slotCoverage = new AtomicInteger();
    private final AtomicLong lastRefreshTimestampSeconds = new AtomicLong();
    private volatile String[] slotNodes = new String[SLOTS];

    public RouteResolver(ProxyProperties properties, MeterRegistry registry) {
        properties.validate();
        this.properties = properties;
        this.defaultCluster = properties.getBackends().getClusters().stream()
                .filter(c -> properties.getRouting().getDefaultCluster().equals(c.getName()))
                .findFirst()
                .orElseThrow();
        registry.gauge("redis.proxy.cluster.slot.coverage", slotCoverage);
        registry.gauge("redis.proxy.cluster.slot.last.refresh.timestamp.seconds", lastRefreshTimestampSeconds);
        registry.gauge("redis.proxy.route.epoch", properties.getRouting(), routing -> routing.getRouteEpoch());
    }

    public String route(RespRequest request) {
        List<String> nodes = defaultCluster.getNodes();
        if (!"cluster".equals(properties.getMode()) || nodes.size() == 1 || request.args().size() < 2) {
            return nodes.getFirst();
        }
        int slot = RedisSlot.slot(request.args().get(1));
        String cached = slotNodes[slot];
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        return nodes.get(slot % nodes.size());
    }

    public void refreshSlots(BackendPool backendPool, Duration timeout) throws Exception {
        if (!"cluster".equals(properties.getMode()) || defaultCluster.getNodes().isEmpty()) {
            return;
        }
        Exception lastError = null;
        for (String seed : defaultCluster.getNodes()) {
            ByteBuf request = Unpooled.wrappedBuffer(CLUSTER_SLOTS);
            ByteBuf response = null;
            try {
                response = backendPool.doRequest(seed, request).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                applyClusterSlots(response);
                backendPool.ensureAll(snapshotNodes());
                return;
            } catch (Exception e) {
                lastError = e;
            } finally {
                request.release();
                if (response != null) {
                    response.release();
                }
            }
        }
        if (lastError != null) {
            throw lastError;
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
        String addr = normalizeAddr(fields[2]);
        String[] next = Arrays.copyOf(slotNodes, slotNodes.length);
        next[slot] = addr;
        slotNodes = next;
        slotCoverage.set(countCovered(next));
        backendPool.ensure(addr);
    }

    public int slotCoverage() {
        return slotCoverage.get();
    }

    public List<String> slotOwners() {
        Set<String> seen = new HashSet<>();
        for (String node : slotNodes) {
            if (node != null && !node.isBlank()) {
                seen.add(node);
            }
        }
        return List.copyOf(seen);
    }

    public List<String> defaultNodes() {
        return List.copyOf(defaultCluster.getNodes());
    }

    String normalizeAddr(String addr) {
        HostPort target = HostPort.parse(addr);
        for (String node : defaultCluster.getNodes()) {
            HostPort configured = HostPort.parse(node);
            if (configured.port() == target.port()) {
                return node;
            }
        }
        return target.host() + ":" + target.port();
    }

    void applyClusterSlots(ByteBuf raw) {
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
            String addr = normalizeAddr(host + ":" + port);
            start = Math.max(0, start);
            end = Math.min(SLOTS - 1, end);
            for (int slot = start; slot <= end; slot++) {
                next[slot] = addr;
            }
        }
        slotNodes = next;
        slotCoverage.set(countCovered(next));
        lastRefreshTimestampSeconds.set(System.currentTimeMillis() / 1000);
    }

    private List<String> snapshotNodes() {
        Map<String, Boolean> seen = new HashMap<>();
        for (String node : slotNodes) {
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
