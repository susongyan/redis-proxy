package com.example.redisproxy.dataplane.netty;

import com.example.redisproxy.dataplane.backend.BackendPool;
import com.example.redisproxy.dataplane.netty.ClientResponseSequencer.PendingResponse;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import com.example.redisproxy.dataplane.router.RouteResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyChannelHandler extends SimpleChannelInboundHandler<RespRequest> {
    private static final AttributeKey<ClientResponseSequencer> SEQUENCER =
            AttributeKey.valueOf("redis.proxy.response.sequencer");

    private final RouteResolver routeResolver;
    private final BackendPool backendPool;
    private final MeterRegistry registry;
    private final AtomicInteger activeConnections;
    private final AtomicInteger pendingClientResponses;
    private final Counter moved;
    private final Counter ask;

    public ProxyChannelHandler(RouteResolver routeResolver, BackendPool backendPool, MeterRegistry registry, AtomicInteger activeConnections, AtomicInteger pendingClientResponses) {
        this.routeResolver = routeResolver;
        this.backendPool = backendPool;
        this.registry = registry;
        this.activeConnections = activeConnections;
        this.pendingClientResponses = pendingClientResponses;
        this.moved = Counter.builder("redis.proxy.moved").register(registry);
        this.ask = Counter.builder("redis.proxy.ask").register(registry);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RespRequest request) {
        Timer.Sample sample = Timer.start(registry);
        ClientResponseSequencer sequencer = ctx.channel().attr(SEQUENCER).get();
        String command = request.command();
        long sequence = sequencer.nextSequence();
        pendingClientResponses.incrementAndGet();
        registry.counter("redis.proxy.requests", "command", command).increment();
        try {
            String backend = routeResolver.route(request);
            backendPool.doRequest(backend, request.raw()).whenComplete((response, error) ->
                    ctx.executor().execute(() -> sequencer.complete(sequence, new PendingResponse(response, error, command, sample), pending -> flush(ctx, pending))));
        } catch (Exception e) {
            ctx.executor().execute(() -> sequencer.complete(sequence, new PendingResponse(null, e, command, sample), pending -> flush(ctx, pending)));
        } finally {
            request.release();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        activeConnections.incrementAndGet();
        ctx.channel().attr(SEQUENCER).set(new ClientResponseSequencer());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        activeConnections.decrementAndGet();
        super.channelInactive(ctx);
    }

    private void flush(ChannelHandlerContext ctx, PendingResponse pending) {
        pendingClientResponses.decrementAndGet();
        if (pending.error() != null) {
            registry.counter("redis.proxy.errors", "type", "backend").increment();
            pending.sample().stop(registry.timer("redis.proxy.request.latency", "command", pending.command()));
            ctx.writeAndFlush(Unpooled.copiedBuffer("-ERR backend unavailable\r\n", StandardCharsets.US_ASCII));
            return;
        }
        ByteBuf response = pending.response();
        if (startsWith(response, "-MOVED ")) {
            moved.increment();
        } else if (startsWith(response, "-ASK ")) {
            ask.increment();
        }
        pending.sample().stop(registry.timer("redis.proxy.request.latency", "command", pending.command()));
        ctx.writeAndFlush(response);
    }

    private static boolean startsWith(ByteBuf bytes, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        if (bytes.readableBytes() < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (bytes.getByte(bytes.readerIndex() + i) != p[i]) {
                return false;
            }
        }
        return true;
    }
}
