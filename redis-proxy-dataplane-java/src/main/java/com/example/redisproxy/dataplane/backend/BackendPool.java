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
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class BackendPool implements AutoCloseable {
    private final ProxyProperties properties;
    private final MeterRegistry registry;
    private final EventLoopGroup group = new NioEventLoopGroup();
    private final Map<String, List<BackendConnection>> pools = new ConcurrentHashMap<>();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicInteger totalInflight = new AtomicInteger();

    public BackendPool(ProxyProperties properties, MeterRegistry registry) {
        this.properties = properties;
        this.registry = registry;
        registry.gauge("redis.proxy.backend.active.connections", activeConnections);
        registry.gauge("redis.proxy.backend.inflight", totalInflight);
        for (ProxyProperties.Cluster cluster : properties.getBackends().getClusters()) {
            int size = Math.max(1, cluster.getPool().getConnectionsPerNode());
            for (String node : cluster.getNodes()) {
                pools.computeIfAbsent(node, ignored -> connectPool(node, size));
            }
        }
    }

    public CompletableFuture<ByteBuf> doRequest(String address, ByteBuf request) {
        List<BackendConnection> connections = pools.computeIfAbsent(address, ignored -> connectPool(address, 1));
        BackendConnection selected = null;
        for (BackendConnection connection : connections) {
            if (!connection.isActive()) {
                continue;
            }
            if (selected == null || connection.inflight() < selected.inflight()) {
                selected = connection;
            }
        }
        if (selected == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("backend unavailable: " + address));
        }
        return selected.send(request);
    }

    private List<BackendConnection> connectPool(String address, int size) {
        List<BackendConnection> connections = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            connections.add(connect(address));
        }
        return connections;
    }

    private BackendConnection connect(String address) {
        String[] parts = address.split(":", 2);
        BackendConnection connection = new BackendConnection(address);
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
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
        return connection;
    }

    @Override
    @PreDestroy
    public void close() {
        pools.values().forEach(list -> list.forEach(BackendConnection::close));
        group.shutdownGracefully();
    }

    private final class BackendConnection extends ChannelInboundHandlerAdapter {
        private final String address;
        private final ArrayDeque<PendingRequest> pending = new ArrayDeque<>();
        private final AtomicInteger inflight = new AtomicInteger();
        private Channel channel;

        private BackendConnection(String address) {
            this.address = address;
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
            failAll(new IllegalStateException("backend closed: " + address));
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
