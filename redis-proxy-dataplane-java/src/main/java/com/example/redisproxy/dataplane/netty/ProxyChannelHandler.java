package com.example.redisproxy.dataplane.netty;

import com.example.redisproxy.dataplane.backend.BackendPool;
import com.example.redisproxy.dataplane.analysis.HotKeyTracker;
import com.example.redisproxy.dataplane.analysis.LargeKeyTracker;
import com.example.redisproxy.dataplane.analysis.SlowQueryTracker;
import com.example.redisproxy.dataplane.governance.GovernancePolicy;
import com.example.redisproxy.dataplane.governance.KeyGovernanceLimiter;
import com.example.redisproxy.dataplane.governance.NamespaceLimiter;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyChannelHandler extends SimpleChannelInboundHandler<RespRequest> {
    private static final AttributeKey<ClientResponseSequencer> SEQUENCER =
            AttributeKey.valueOf("redis.proxy.response.sequencer");
    private static final AttributeKey<String> NAMESPACE =
            AttributeKey.valueOf("redis.proxy.namespace");
    private static final AttributeKey<PipelineBatcher> BATCHER =
            AttributeKey.valueOf("redis.proxy.pipeline.batcher");
    private static final AttributeKey<AtomicInteger> CLIENT_PENDING =
            AttributeKey.valueOf("redis.proxy.client.pending");

    private final RouteResolver routeResolver;
    private final BackendPool backendPool;
    private final ClusterSlotRefresher slotRefresher;
    private final NamespaceLimiter namespaceLimiter;
    private final KeyGovernanceLimiter keyGovernanceLimiter;
    private final HotKeyTracker hotKeyTracker;
    private final LargeKeyTracker largeKeyTracker;
    private final SlowQueryTracker slowQueryTracker;
    private final PipelineStats pipelineStats;
    private final MeterRegistry registry;
    private final AtomicInteger activeConnections;
    private final AtomicInteger pendingClientResponses;
    private final Counter moved;
    private final Counter ask;

    public ProxyChannelHandler(RouteResolver routeResolver, BackendPool backendPool, ClusterSlotRefresher slotRefresher, NamespaceLimiter namespaceLimiter, KeyGovernanceLimiter keyGovernanceLimiter, HotKeyTracker hotKeyTracker, LargeKeyTracker largeKeyTracker, SlowQueryTracker slowQueryTracker, PipelineStats pipelineStats, MeterRegistry registry, AtomicInteger activeConnections, AtomicInteger pendingClientResponses) {
        this.routeResolver = routeResolver;
        this.backendPool = backendPool;
        this.slotRefresher = slotRefresher;
        this.namespaceLimiter = namespaceLimiter;
        this.keyGovernanceLimiter = keyGovernanceLimiter;
        this.hotKeyTracker = hotKeyTracker;
        this.largeKeyTracker = largeKeyTracker;
        this.slowQueryTracker = slowQueryTracker;
        this.pipelineStats = pipelineStats;
        this.registry = registry;
        this.activeConnections = activeConnections;
        this.pendingClientResponses = pendingClientResponses;
        this.moved = Counter.builder("redis.proxy.moved").register(registry);
        this.ask = Counter.builder("redis.proxy.ask").register(registry);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RespRequest request) {
        Timer.Sample sample = Timer.start(registry);
        long startNanos = System.nanoTime();
        ClientResponseSequencer sequencer = ctx.channel().attr(SEQUENCER).get();
        AtomicInteger clientPending = ctx.channel().attr(CLIENT_PENDING).get();
        String command = request.command();
        long sequence = sequencer.nextSequence();
        clientPending.incrementAndGet();
        pendingClientResponses.incrementAndGet();
        registry.counter("redis.proxy.requests", "command", command).increment();
        if (clientPending.get() > routeResolver.limits().getMaxPipelineDepth()) {
            registry.counter("redis.proxy.errors", "type", "pipeline_limit").increment();
            complete(ctx, sequencer, sequence, new PendingResponse(Unpooled.copiedBuffer("-ERR pipeline depth exceeded\r\n", StandardCharsets.US_ASCII), null, command, sample, null, null, startNanos, 0));
            request.release();
            return;
        }
        boolean namespaceAcquired = false;
        String requestNamespace = "";
        try {
            if (routeResolver.governance().isEnabled() && "AUTH".equals(command)) {
                GovernancePolicy.AuthResult auth = GovernancePolicy.authenticate(routeResolver.governance(), request);
                registry.counter("redis.proxy.auth", "namespace", auth.namespace(), "result", auth.result()).increment();
                if (auth.allowed()) {
                    String currentNamespace = ctx.channel().attr(NAMESPACE).get();
                    NamespaceLimiter.LimitResult limit = namespaceLimiter.bind(currentNamespace == null ? "" : currentNamespace, GovernancePolicy.namespaceConfig(routeResolver.governance(), auth.namespace()));
                    if (!limit.allowed()) {
                        registry.counter("redis.proxy.auth", "namespace", auth.namespace(), "result", limit.reason()).increment();
                        registry.counter("redis.proxy.governance.reject", "namespace", auth.namespace(), "command", command, "reason", limit.reason()).increment();
                        registry.counter("redis.proxy.namespace.limit.reject", "namespace", auth.namespace(), "limit", namespaceLimitLabel(limit.reason())).increment();
                        complete(ctx, sequencer, sequence, new PendingResponse(Unpooled.copiedBuffer("-ERR namespace connection limit exceeded\r\n", StandardCharsets.US_ASCII), null, command, sample, null, null, startNanos, 0));
                        return;
                    }
                    ctx.channel().attr(NAMESPACE).set(auth.namespace());
                }
                complete(ctx, sequencer, sequence, new PendingResponse(Unpooled.copiedBuffer(auth.response(), StandardCharsets.US_ASCII), null, command, sample, null, null, startNanos, 0));
                return;
            }
            String namespace = ctx.channel().attr(NAMESPACE).get();
            GovernancePolicy.Decision governance = GovernancePolicy.evaluate(routeResolver.governance(), namespace == null ? "" : namespace, request);
            if (governance.warn()) {
                registry.counter("redis.proxy.governance.warn", "namespace", governance.namespace(), "command", command, "reason", governance.warnReason()).increment();
            }
            if (!GovernancePolicy.ALLOW.equals(governance.action())) {
                registry.counter("redis.proxy.governance.reject", "namespace", governance.namespace(), "command", command, "reason", governance.reason()).increment();
                complete(ctx, sequencer, sequence, new PendingResponse(Unpooled.copiedBuffer(governance.response(), StandardCharsets.US_ASCII), null, command, sample, null, null, startNanos, 0));
                return;
            }
            var namespaceConfig = GovernancePolicy.namespaceConfig(routeResolver.governance(), namespace == null ? "" : namespace);
            NamespaceLimiter.LimitResult limit = namespaceLimiter.allowRequest(namespaceConfig);
            if (!limit.allowed()) {
                registry.counter("redis.proxy.governance.reject", "namespace", namespace == null ? "" : namespace, "command", command, "reason", limit.reason()).increment();
                registry.counter("redis.proxy.namespace.limit.reject", "namespace", namespace == null ? "" : namespace, "limit", namespaceLimitLabel(limit.reason())).increment();
                complete(ctx, sequencer, sequence, new PendingResponse(Unpooled.copiedBuffer("-ERR request limited by proxy governance\r\n", StandardCharsets.US_ASCII), null, command, sample, null, null, startNanos, 0));
                return;
            }
            namespaceAcquired = true;
            requestNamespace = namespace == null ? "" : namespace;
            KeyGovernanceLimiter.Decision keyDecision = keyGovernanceLimiter.evaluate(routeResolver.governance(), namespaceConfig, request);
            if (!keyDecision.allowed()) {
                namespaceLimiter.finishRequest(requestNamespace);
                namespaceAcquired = false;
                registry.counter("redis.proxy.key.governance.reject", "namespace", requestNamespace, "rule", keyDecision.rule(), "command", command, "reason", keyDecision.reason()).increment();
                complete(ctx, sequencer, sequence, new PendingResponse(Unpooled.copiedBuffer(keyDecision.response(), StandardCharsets.US_ASCII), null, command, sample, null, null, startNanos, 0));
                return;
            }
            hotKeyTracker.observe(requestNamespace, request);
            LargeKeyTracker.Context largeKeyContext = largeKeyTracker.context(requestNamespace, request);
            largeKeyTracker.observeRequest(largeKeyContext, request.raw().readableBytes());
            SlowQueryTracker.Context slowQueryContext = slowQueryTracker.context(requestNamespace, request);
            RouteDecision decision = routeResolver.routeDecision(request, requestNamespace);
            registry.counter("redis.proxy.route.decisions", "cluster", decision.cluster(), "rule", decision.rule()).increment();
            ByteBuf retryRaw = request.raw().retainedDuplicate();
            String backendNamespace = requestNamespace;
            long backendStartNanos = System.nanoTime();
            int requestAffinity = routeResolver.backendAffinity(request, ctx.channel().id().asLongText().hashCode());
            backendPool.doRequest(decision.address(), request.raw(), requestAffinity).whenComplete((response, error) ->
                    ctx.executor().execute(() -> {
                        namespaceLimiter.finishRequest(backendNamespace);
                        completeBackendResult(ctx, sequencer, sequence, command, sample, startNanos, backendStartNanos, decision, retryRaw, response, error, false, largeKeyContext, slowQueryContext);
                    }));
        } catch (Exception e) {
            if (namespaceAcquired) {
                namespaceLimiter.finishRequest(requestNamespace);
            }
            ctx.executor().execute(() -> complete(ctx, sequencer, sequence, new PendingResponse(null, e, command, sample, null, null, startNanos, 0)));
        } finally {
            request.release();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        activeConnections.incrementAndGet();
        ctx.channel().attr(SEQUENCER).set(new ClientResponseSequencer());
        ctx.channel().attr(BATCHER).set(new PipelineBatcher(ctx, pipelineStats));
        ctx.channel().attr(CLIENT_PENDING).set(new AtomicInteger());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        activeConnections.decrementAndGet();
        PipelineBatcher batcher = ctx.channel().attr(BATCHER).get();
        if (batcher != null) {
            batcher.flushNow();
        }
        String namespace = ctx.channel().attr(NAMESPACE).get();
        namespaceLimiter.unbind(namespace == null ? "" : namespace);
        super.channelInactive(ctx);
    }

    private void completeBackendResult(ChannelHandlerContext ctx, ClientResponseSequencer sequencer, long sequence, String command, Timer.Sample sample, long startNanos, long backendStartNanos, RouteDecision decision, ByteBuf retryRaw, ByteBuf response, Throwable error, boolean askRetried, LargeKeyTracker.Context largeKeyContext, SlowQueryTracker.Context slowQueryContext) {
        if (error != null || !startsWith(response, "-ASK ")) {
            retryRaw.release();
            complete(ctx, sequencer, sequence, new PendingResponse(response, error, command, sample, largeKeyContext, slowQueryContext, startNanos, System.nanoTime() - backendStartNanos));
            return;
        }
        if (askRetried) {
            retryRaw.release();
            complete(ctx, sequencer, sequence, new PendingResponse(response, null, command, sample, largeKeyContext, slowQueryContext, startNanos, System.nanoTime() - backendStartNanos));
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
            complete(ctx, sequencer, sequence, new PendingResponse(null, e, command, sample, largeKeyContext, slowQueryContext, startNanos, System.nanoTime() - backendStartNanos));
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
                    completeBackendResult(ctx, sequencer, sequence, command, sample, startNanos, backendStartNanos, decision, retryRaw, retryResponse, retryError, true, largeKeyContext, slowQueryContext);
                }));
    }

    private void complete(ChannelHandlerContext ctx, ClientResponseSequencer sequencer, long sequence, PendingResponse pending) {
        ClientResponseSequencer.Result result = sequencer.complete(sequence, pending);
        if (result.blocked()) {
            pipelineStats.observeHolBlocked("backend_pending", result.pendingResponses());
        } else {
            pipelineStats.observeBuffered(result.pendingResponses());
        }
        for (PendingResponse flushed : result.flushed()) {
            writePending(ctx, flushed);
        }
    }

    private void writePending(ChannelHandlerContext ctx, PendingResponse pending) {
        pendingClientResponses.decrementAndGet();
        AtomicInteger clientPending = ctx.channel().attr(CLIENT_PENDING).get();
        if (clientPending != null) {
            clientPending.decrementAndGet();
        }
        pipelineStats.observeHolWaitMillis(elapsedMillis(pending.completedNanos()));
        PipelineBatcher batcher = ctx.channel().attr(BATCHER).get();
        if (pending.error() != null) {
            registry.counter("redis.proxy.errors", "type", "backend").increment();
            pending.sample().stop(registry.timer("redis.proxy.request.latency", "command", pending.command()));
            slowQueryTracker.observe(pending.slowQueryContext(), elapsedMillis(pending.startNanos()), nanosToMillis(pending.backendNanos()));
            writeResponse(ctx, batcher, Unpooled.copiedBuffer("-ERR backend unavailable\r\n", StandardCharsets.US_ASCII));
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
        observeResponseSize(pending.command(), response.readableBytes());
        largeKeyTracker.observeResponse(pending.largeKeyContext(), response.readableBytes());
        slowQueryTracker.observe(pending.slowQueryContext(), elapsedMillis(pending.startNanos()), nanosToMillis(pending.backendNanos()));
        pending.sample().stop(registry.timer("redis.proxy.request.latency", "command", pending.command()));
        writeResponse(ctx, batcher, response);
    }

    private void writeResponse(ChannelHandlerContext ctx, PipelineBatcher batcher, ByteBuf response) {
        if (batcher == null) {
            ctx.writeAndFlush(response);
            pipelineStats.observeFlushBatch(1);
            return;
        }
        batcher.write(response, routeResolver.limits().getPipelineFlushBatchSize(), routeResolver.limits().getPipelineFlushMaxDelayMillis());
    }

    private void observeResponseSize(String command, int size) {
        registry.summary("redis.proxy.response.bytes", "command", command).record(size);
        int threshold = routeResolver.limits().getLargeResponseBytes();
        if (threshold > 0 && size >= threshold) {
            registry.counter("redis.proxy.large.response", "command", command).increment();
        }
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

    private static String namespaceLimitLabel(String reason) {
        return switch (reason) {
            case "connection_limit" -> "connections";
            case "qps_limit" -> "qps";
            case "inflight_limit" -> "inflight";
            default -> reason;
        };
    }

    private static long elapsedMillis(long startNanos) {
        if (startNanos <= 0) {
            return 0;
        }
        return nanosToMillis(System.nanoTime() - startNanos);
    }

    private static long nanosToMillis(long nanos) {
        if (nanos <= 0) {
            return 0;
        }
        return nanos / 1_000_000;
    }

    private static final class PipelineBatcher {
        private final ChannelHandlerContext ctx;
        private final PipelineStats stats;
        private int pendingWrites;
        private ScheduledFuture<?> scheduledFlush;

        private PipelineBatcher(ChannelHandlerContext ctx, PipelineStats stats) {
            this.ctx = ctx;
            this.stats = stats;
        }

        private void write(ByteBuf response, int batchSize, int maxDelayMillis) {
            ctx.write(response);
            pendingWrites++;
            if (pendingWrites >= Math.max(1, batchSize) || maxDelayMillis <= 0) {
                flushNow();
                return;
            }
            if (scheduledFlush == null || scheduledFlush.isDone()) {
                scheduledFlush = ctx.executor().schedule(this::flushNow, maxDelayMillis, TimeUnit.MILLISECONDS);
            }
        }

        private void flushNow() {
            if (pendingWrites <= 0) {
                return;
            }
            int flushed = pendingWrites;
            pendingWrites = 0;
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }
            ctx.flush();
            stats.observeFlushBatch(flushed);
        }
    }
}
