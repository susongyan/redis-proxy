package com.example.redisproxy.dataplane.backend;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.protocol.RespFrameDecoder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import jakarta.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class BackendPool implements AutoCloseable {
    private static final int INITIAL_RECONNECT_DELAY_SECONDS = 1;
    private static final int MAX_RECONNECT_DELAY_SECONDS = 30;
    private static final int GLOBAL_RECONNECT_LIMIT = 64;
    private static final int NODE_RECONNECT_LIMIT = 2;

    private final ProxyProperties properties;
    private final MeterRegistry registry;
    private final EventLoopGroup group = new NioEventLoopGroup();
    private final ScheduledExecutorService reconnectScheduler = Executors.newScheduledThreadPool(8, r -> {
        Thread thread = new Thread(r, "backend-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, List<BackendConnection>> pools = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> nodeActiveConnections = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> nodeReconnecting = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> nodeReconnectLimits = new ConcurrentHashMap<>();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicInteger totalInflight = new AtomicInteger();
    private final AtomicInteger reconnecting = new AtomicInteger();
    private final Semaphore globalReconnectLimit = new Semaphore(GLOBAL_RECONNECT_LIMIT);
    private volatile boolean closing;

    public BackendPool(ProxyProperties properties, MeterRegistry registry) {
        this.properties = properties;
        this.registry = registry;
        registry.gauge("redis.proxy.backend.active.connections", activeConnections);
        registry.gauge("redis.proxy.backend.inflight", totalInflight);
        registry.gauge("redis.proxy.backend.reconnecting", reconnecting);
        for (ProxyProperties.Cluster cluster : properties.getBackends().getClusters()) {
            int size = Math.max(1, cluster.getPool().getConnectionsPerNode());
            for (String node : cluster.getNodes()) {
                registerNodeMetrics(node, size);
                pools.computeIfAbsent(node, ignored -> connectPool(node, size));
            }
        }
    }

    public CompletableFuture<ByteBuf> doRequest(String address, ByteBuf request) {
        return doRequest(address, request, 0);
    }

    public CompletableFuture<ByteBuf> doRequest(String address, ByteBuf request, int affinity) {
        List<BackendConnection> connections = pools.computeIfAbsent(address, ignored -> connectPool(address, 1));
        int start = Math.floorMod(affinity, connections.size());
        for (int i = 0; i < connections.size(); i++) {
            BackendConnection connection = connections.get((start + i) % connections.size());
            if (connection.isActive()) {
                return connection.send(request);
            }
        }
        return CompletableFuture.failedFuture(new IllegalStateException("backend unavailable: " + address));
    }

    public void ensure(String address) {
        registerNodeMetrics(address, 1);
        pools.computeIfAbsent(address, ignored -> connectPool(address, 1));
    }

    public void ensureAll(List<String> addresses) {
        for (String address : addresses) {
            ensure(address);
        }
    }

    public int activeCount(String address) {
        AtomicInteger value = nodeActiveConnections.get(address);
        return value == null ? 0 : value.get();
    }

    public int desiredCount(String address) {
        List<BackendConnection> connections = pools.get(address);
        return connections == null ? 0 : connections.size();
    }

    public boolean hasActive(String address) {
        return activeCount(address) > 0;
    }

    private List<BackendConnection> connectPool(String address, int size) {
        List<BackendConnection> connections = new CopyOnWriteArrayList<>();
        for (int i = 0; i < size; i++) {
            connections.add(connect(address, i));
        }
        return connections;
    }

    private BackendConnection connect(String address) {
        return connect(address, 0);
    }

    private BackendConnection connect(String address, int index) {
        String[] parts = address.split(":", 2);
        BackendConnection connection = new BackendConnection(address, index);
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new RespFrameDecoder(properties.getLimits().getMaxResponseBytes()));
                        ch.pipeline().addLast(connection);
                    }
                });
        Channel channel = bootstrap.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1]))).syncUninterruptibly().channel();
        connection.bind(channel);
        activeConnections.incrementAndGet();
        nodeActiveConnections(address).incrementAndGet();
        return connection;
    }

    private void registerNodeMetrics(String address, int desiredConnections) {
        nodeActiveConnections.computeIfAbsent(address, node -> {
            AtomicInteger value = new AtomicInteger();
            registry.gauge("redis.proxy.backend.active.connections.by.node", List.of(io.micrometer.core.instrument.Tag.of("node", node)), value);
            registry.gauge("redis.proxy.backend.desired.connections", List.of(io.micrometer.core.instrument.Tag.of("node", node)), desiredConnections);
            return value;
        });
        nodeReconnecting.computeIfAbsent(address, node -> {
            AtomicInteger value = new AtomicInteger();
            registry.gauge("redis.proxy.backend.reconnecting.by.node", List.of(io.micrometer.core.instrument.Tag.of("node", node)), value);
            return value;
        });
        nodeReconnectLimits.computeIfAbsent(address, ignored -> new Semaphore(NODE_RECONNECT_LIMIT));
    }

    private void scheduleReconnect(String address, int index) {
        scheduleReconnect(address, index, INITIAL_RECONNECT_DELAY_SECONDS);
    }

    private void scheduleReconnect(String address, int index, int delaySeconds) {
        if (closing) {
            return;
        }
        reconnecting.incrementAndGet();
        nodeReconnecting(address).incrementAndGet();
        reconnectScheduler.schedule(() -> reconnect(address, index, delaySeconds), delaySeconds, TimeUnit.SECONDS);
    }

    private void reconnect(String address, int index, int delaySeconds) {
        if (closing) {
            decrementReconnecting(address);
            return;
        }
        Semaphore nodeLimit = nodeReconnectLimits.computeIfAbsent(address, ignored -> new Semaphore(NODE_RECONNECT_LIMIT));
        if (!globalReconnectLimit.tryAcquire()) {
            scheduleReconnect(address, index, 1);
            decrementReconnecting(address);
            return;
        }
        if (!nodeLimit.tryAcquire()) {
            globalReconnectLimit.release();
            scheduleReconnect(address, index, 1);
            decrementReconnecting(address);
            return;
        }
        try {
            BackendConnection replacement = connect(address, index);
            List<BackendConnection> connections = pools.get(address);
            if (connections == null || index >= connections.size()) {
                replacement.close();
                return;
            }
            connections.set(index, replacement);
            registry.counter("redis.proxy.backend.reconnect", "node", address, "result", "success").increment();
        } catch (Exception e) {
            registry.counter("redis.proxy.backend.reconnect", "node", address, "result", "error").increment();
            if (!closing) {
                scheduleReconnect(address, index, nextDelay(delaySeconds));
            }
        } finally {
            nodeLimit.release();
            globalReconnectLimit.release();
            decrementReconnecting(address);
        }
    }

    private int nextDelay(int delaySeconds) {
        int next = Math.min(delaySeconds * 2, MAX_RECONNECT_DELAY_SECONDS);
        int jitter = ThreadLocalRandom.current().nextInt(0, Math.max(1, next / 5) + 1);
        return Math.min(MAX_RECONNECT_DELAY_SECONDS, next + jitter);
    }

    private AtomicInteger nodeActiveConnections(String address) {
        registerNodeMetrics(address, 1);
        return nodeActiveConnections.get(address);
    }

    private AtomicInteger nodeReconnecting(String address) {
        registerNodeMetrics(address, 1);
        return nodeReconnecting.get(address);
    }

    private void decrementReconnecting(String address) {
        decrementNonNegative(reconnecting);
        decrementNonNegative(nodeReconnecting(address));
    }

    int reconnectingCount() {
        return reconnecting.get();
    }

    int nodeReconnectingCount(String address) {
        AtomicInteger value = nodeReconnecting.get(address);
        return value == null ? 0 : value.get();
    }

    private static void decrementNonNegative(AtomicInteger value) {
        value.updateAndGet(current -> current <= 0 ? 0 : current - 1);
    }

    @Override
    @PreDestroy
    public void close() {
        closing = true;
        reconnectScheduler.shutdownNow();
        pools.values().forEach(list -> list.forEach(BackendConnection::close));
        group.shutdownGracefully();
    }

    private final class BackendConnection extends ChannelInboundHandlerAdapter {
        private final String address;
        private final int index;
        private final ArrayDeque<PendingRequest> pending = new ArrayDeque<>();
        private final AtomicInteger inflight = new AtomicInteger();
        private Channel channel;

        private BackendConnection(String address) {
            this(address, 0);
        }

        private BackendConnection(String address, int index) {
            this.address = address;
            this.index = index;
        }

        private void bind(Channel channel) {
            this.channel = channel;
        }

        private boolean isActive() {
            return channel != null && channel.isActive();
        }

        private int inflight() {
            return inflight.get();
        }

        private CompletableFuture<ByteBuf> send(ByteBuf request) {
            int maxInflight = properties.getBackends().getClusters().stream()
                    .flatMap(cluster -> cluster.getNodes().stream()
                            .filter(address::equals)
                            .map(node -> cluster.getPool().getMaxInflightPerConnection()))
                    .findFirst()
                    .orElse(1024);
            if (inflight.incrementAndGet() > maxInflight) {
                inflight.decrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("backend inflight limit exceeded: " + address));
            }
            totalInflight.incrementAndGet();
            CompletableFuture<ByteBuf> future = new CompletableFuture<>();
            PendingRequest pendingRequest = new PendingRequest(future, Timer.start(registry));
            ByteBuf outbound = request.retainedDuplicate();
            channel.eventLoop().execute(() -> {
                pending.add(pendingRequest);
                channel.writeAndFlush(outbound).addListener((ChannelFutureListener) writeFuture -> {
                    if (!writeFuture.isSuccess()) {
                        pending.remove(pendingRequest);
                        completeFailure(pendingRequest, writeFuture.cause());
                    }
                });
            });
            return future;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf response = (ByteBuf) msg;
            PendingRequest request = pending.poll();
            if (request == null) {
                response.release();
                return;
            }
            request.sample.stop(registry.timer("redis.proxy.backend.latency", "backend", address));
            inflight.decrementAndGet();
            totalInflight.decrementAndGet();
            request.future.complete(response);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            failAll(cause);
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            activeConnections.decrementAndGet();
            nodeActiveConnections(address).decrementAndGet();
            failAll(new IllegalStateException("backend closed: " + address));
            scheduleReconnect(address, index);
        }

        private void failAll(Throwable cause) {
            PendingRequest request;
            while ((request = pending.poll()) != null) {
                completeFailure(request, cause);
            }
        }

        private void completeFailure(PendingRequest request, Throwable cause) {
            inflight.decrementAndGet();
            totalInflight.decrementAndGet();
            request.future.completeExceptionally(cause);
        }

        private void close() {
            if (channel != null) {
                channel.close();
            }
        }
    }

    private record PendingRequest(CompletableFuture<ByteBuf> future, Timer.Sample sample) {}
}
