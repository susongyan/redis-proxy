package com.example.redisproxy.dataplane.netty;

import com.example.redisproxy.dataplane.backend.BackendPool;
import com.example.redisproxy.dataplane.analysis.HotKeyTracker;
import com.example.redisproxy.dataplane.analysis.LargeKeyTracker;
import com.example.redisproxy.dataplane.analysis.SlowQueryTracker;
import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.governance.KeyGovernanceLimiter;
import com.example.redisproxy.dataplane.governance.NamespaceLimiter;
import com.example.redisproxy.dataplane.protocol.RespRequestDecoder;
import com.example.redisproxy.dataplane.router.ClusterSlotRefresher;
import com.example.redisproxy.dataplane.router.RouteResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class NettyProxyServer {
    private final ProxyProperties properties;
    private final RouteResolver routeResolver;
    private final ClusterSlotRefresher slotRefresher;
    private final BackendPool backendPool;
    private final NamespaceLimiter namespaceLimiter;
    private final KeyGovernanceLimiter keyGovernanceLimiter;
    private final HotKeyTracker hotKeyTracker;
    private final LargeKeyTracker largeKeyTracker;
    private final SlowQueryTracker slowQueryTracker;
    private final PipelineStats pipelineStats;
    private final MeterRegistry registry;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicInteger pendingClientResponses = new AtomicInteger();

    public NettyProxyServer(ProxyProperties properties, RouteResolver routeResolver, ClusterSlotRefresher slotRefresher, BackendPool backendPool, NamespaceLimiter namespaceLimiter, KeyGovernanceLimiter keyGovernanceLimiter, HotKeyTracker hotKeyTracker, LargeKeyTracker largeKeyTracker, SlowQueryTracker slowQueryTracker, PipelineStats pipelineStats, MeterRegistry registry) {
        this.properties = properties;
        this.routeResolver = routeResolver;
        this.slotRefresher = slotRefresher;
        this.backendPool = backendPool;
        this.namespaceLimiter = namespaceLimiter;
        this.keyGovernanceLimiter = keyGovernanceLimiter;
        this.hotKeyTracker = hotKeyTracker;
        this.largeKeyTracker = largeKeyTracker;
        this.slowQueryTracker = slowQueryTracker;
        this.pipelineStats = pipelineStats;
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws InterruptedException {
        HostPort listen = HostPort.parse(properties.getServer().getListen());
        bossGroup = new NioEventLoopGroup(Math.max(1, properties.getServer().getBossThreads()));
        workerGroup = new NioEventLoopGroup(properties.getServer().getWorkerThreads() > 0 ? properties.getServer().getWorkerThreads() : 0);
        registry.gauge("redis.proxy.active.connections", activeConnections);
        registry.gauge("redis.proxy.client.pending.responses", pendingClientResponses);
        registry.gauge("redis.proxy.large.response.threshold.bytes", routeResolver, resolver -> (double) resolver.limits().getLargeResponseBytes());
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new RespRequestDecoder(properties.getLimits().getMaxRequestBytes()));
                        ch.pipeline().addLast(new ProxyChannelHandler(routeResolver, backendPool, slotRefresher, namespaceLimiter, keyGovernanceLimiter, hotKeyTracker, largeKeyTracker, slowQueryTracker, pipelineStats, registry, activeConnections, pendingClientResponses));
                    }
                });
        serverChannel = bootstrap.bind(new InetSocketAddress(listen.host(), listen.port())).sync().channel();
    }

    @PreDestroy
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    private record HostPort(String host, int port) {
        static HostPort parse(String value) {
            String[] parts = value.split(":", 2);
            return new HostPort(parts[0], Integer.parseInt(parts[1]));
        }
    }
}
