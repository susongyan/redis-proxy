package com.example.redisproxy.dataplane.netty;

import com.example.redisproxy.dataplane.backend.BackendPool;
import com.example.redisproxy.dataplane.governance.GovernancePolicy;
import com.example.redisproxy.dataplane.netty.ClientResponseSequencer.PendingResponse;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import com.example.redisproxy.dataplane.router.ClusterSlotRefresher;
import com.example.redisproxy.dataplane.router.RouteResolver.RouteDecision;
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
    private static final AttributeKey<String> NAMESPACE =
            AttributeKey.valueOf("redis.proxy.namespace");

    private final RouteResolver routeResolver;
    private final BackendPool backendPool;
    private final ClusterSlotRefresher slotRefresher;
    private final MeterRegistry registry;
    private final AtomicInteger activeConnections;
    private final AtomicInteger pendingClientResponses;
    private final Counter moved;
    private final Counter ask;

    public ProxyChannelHandler(RouteResolver routeResolver, BackendPool backendPool, ClusterSlotRefresher slotRefresher, MeterRegistry registry, AtomicInteger activeConnections, AtomicInteger pendingClientResponses) {
        this.routeResolver = routeResolver;
        this.backendPool = backendPool;
        this.slotRefresher = slotRefresher;
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
            if (routeResolver.governance().isEnabled() && "AUTH".equals(command)) {
                GovernancePolicy.AuthResult auth = GovernancePolicy.authenticate(routeResolver.governance(), request);
                registry.counter("redis.proxy.auth", "namespace", auth.namespace(), "result", auth.result()).increment();
                if (auth.allowed()) {
                    ctx.channel().attr(NAMESPACE).set(auth.namespace());
                }
                sequencer.complete(sequence, new PendingResponse(Unpooled.copiedBuffer(auth.response(), StandardCharsets.US_ASCII), null, command, sample), pending -> flush(ctx, pending));
                return;
            }
            String namespace = ctx.channel().attr(NAMESPACE).get();
            GovernancePolicy.Decision governance = GovernancePolicy.evaluate(routeResolver.governance(), namespace == null ? "" : namespace, request);
            if (governance.warn()) {
                registry.counter("redis.proxy.governance.warn", "namespace", governance.namespace(), "command", command, "reason", governance.warnReason()).increment();
            }
            if (!GovernancePolicy.ALLOW.equals(governance.action())) {
                registry.counter("redis.proxy.governance.reject", "namespace", governance.namespace(), "command", command, "reason", governance.reason()).increment();
                sequencer.complete(sequence, new PendingResponse(Unpooled.copiedBuffer(governance.response(), StandardCharsets.US_ASCII), null, command, sample), pending -> flush(ctx, pending));
                return;
            }
            RouteDecision decision = routeResolver.routeDecision(request);
            registry.counter("redis.proxy.route.decisions", "cluster", decision.cluster(), "rule", decision.rule()).increment();
            ByteBuf retryRaw = request.raw().retainedDuplicate();
            backendPool.doRequest(decision.address(), request.raw(), ctx.channel().id().asLongText().hashCode()).whenComplete((response, error) ->
                    ctx.executor().execute(() -> completeBackendResult(ctx, sequencer, sequence, command, sample, decision, retryRaw, response, error, false)));
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

    private void completeBackendResult(ChannelHandlerContext ctx, ClientResponseSequencer sequencer, long sequence, String command, Timer.Sample sample, RouteDecision decision, ByteBuf retryRaw, ByteBuf response, Throwable error, boolean askRetried) {
        if (error != null || !startsWith(response, "-ASK ")) {
            retryRaw.release();
            sequencer.complete(sequence, new PendingResponse(response, error, command, sample), pending -> flush(ctx, pending));
            return;
        }
        if (askRetried) {
            retryRaw.release();
            sequencer.complete(sequence, new PendingResponse(response, null, command, sample), pending -> flush(ctx, pending));
            return;
        }
        ask.increment();
        String address;
        try {
            address = routeResolver.askTarget(response, decision.cluster(), backendPool);
        } catch (Exception e) {
            response.release();
            retryRaw.release();
            registry.counter("redis.proxy.ask.redirect", "result", "error").increment();
            sequencer.complete(sequence, new PendingResponse(null, e, command, sample), pending -> flush(ctx, pending));
            return;
        }
        response.release();
        backendPool.doRequestWithAsking(address, retryRaw, ctx.channel().id().asLongText().hashCode()).whenComplete((retryResponse, retryError) ->
                ctx.executor().execute(() -> {
                    if (retryError != null) {
                        registry.counter("redis.proxy.ask.redirect", "result", "error").increment();
                    } else if (startsWith(retryResponse, "-ASK ")) {
                        registry.counter("redis.proxy.ask.redirect", "result", "loop_prevented").increment();
                    } else {
                        registry.counter("redis.proxy.ask.redirect", "result", "success").increment();
                    }
                    completeBackendResult(ctx, sequencer, sequence, command, sample, decision, retryRaw, retryResponse, retryError, true);
                }));
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
            routeResolver.updateMoved(response, backendPool);
            slotRefresher.triggerMovedRefresh();
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
