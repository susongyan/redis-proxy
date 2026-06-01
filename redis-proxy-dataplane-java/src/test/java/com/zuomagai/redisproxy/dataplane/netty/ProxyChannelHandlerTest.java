package com.zuomagai.redisproxy.dataplane.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zuomagai.redisproxy.dataplane.analysis.HotKeyTracker;
import com.zuomagai.redisproxy.dataplane.analysis.LargeKeyTracker;
import com.zuomagai.redisproxy.dataplane.analysis.SlowQueryTracker;
import com.zuomagai.redisproxy.dataplane.backend.BackendPool;
import com.zuomagai.redisproxy.dataplane.config.ProxyProperties;
import com.zuomagai.redisproxy.dataplane.governance.KeyGovernanceLimiter;
import com.zuomagai.redisproxy.dataplane.governance.NamespaceLimiter;
import com.zuomagai.redisproxy.dataplane.protocol.RespRequest;
import com.zuomagai.redisproxy.dataplane.router.ClusterSlotRefresher;
import com.zuomagai.redisproxy.dataplane.router.RouteResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProxyChannelHandlerTest {
    @Test
    void rejectsRequestsBeyondMaxPipelineDepthWithoutForwardingToBackend() {
        ProxyProperties properties = properties(1, 16, 0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RouteResolver routeResolver = new RouteResolver(properties, registry);
        BackendPool backendPool = mock(BackendPool.class);
        CompletableFuture<ByteBuf> firstBackend = new CompletableFuture<>();
        when(backendPool.doRequest(anyString(), any(ByteBuf.class), anyInt())).thenReturn(firstBackend);
        AtomicInteger pending = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyChannelHandler(
                routeResolver,
                backendPool,
                mock(ClusterSlotRefresher.class),
                new NamespaceLimiter(registry),
                new KeyGovernanceLimiter(registry),
                mock(HotKeyTracker.class),
                mock(LargeKeyTracker.class),
                mock(SlowQueryTracker.class),
                new PipelineStats(registry),
                registry,
                new AtomicInteger(),
                pending));

        assertThat(channel.writeInbound(request("GET", "first"))).isFalse();
        assertThat(pending).hasValue(1);

        assertThat(channel.writeInbound(request("GET", "second"))).isFalse();
        ByteBuf noneBeforeFirstCompletion = channel.readOutbound();
        assertThat(noneBeforeFirstCompletion).isNull();
        assertThat(pending.get()).isEqualTo(2);
        verify(backendPool, times(1)).doRequest(anyString(), any(ByteBuf.class), anyInt());
        assertThat(registry.counter("redis.proxy.errors", "type", "pipeline_limit").count()).isEqualTo(1.0);

        firstBackend.complete(Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.US_ASCII));
        channel.runPendingTasks();

        ByteBuf first = channel.readOutbound();
        ByteBuf second = channel.readOutbound();
        assertThat(first.toString(StandardCharsets.US_ASCII)).isEqualTo("+OK\r\n");
        assertThat(second.toString(StandardCharsets.US_ASCII)).isEqualTo("-ERR pipeline depth exceeded\r\n");
        first.release();
        second.release();
        ByteBuf noneAfterFlush = channel.readOutbound();
        assertThat(noneAfterFlush).isNull();
        assertThat(pending.get()).isZero();
        channel.finishAndReleaseAll();
    }

    @Test
    void batchesClientFlushUntilBatchSizeIsReached() {
        ProxyProperties properties = properties(1024, 2, 1000);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RouteResolver routeResolver = new RouteResolver(properties, registry);
        BackendPool backendPool = mock(BackendPool.class);
        CompletableFuture<ByteBuf> firstBackend = new CompletableFuture<>();
        CompletableFuture<ByteBuf> secondBackend = new CompletableFuture<>();
        when(backendPool.doRequest(anyString(), any(ByteBuf.class), anyInt())).thenReturn(firstBackend, secondBackend);
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyChannelHandler(
                routeResolver,
                backendPool,
                mock(ClusterSlotRefresher.class),
                new NamespaceLimiter(registry),
                new KeyGovernanceLimiter(registry),
                mock(HotKeyTracker.class),
                mock(LargeKeyTracker.class),
                mock(SlowQueryTracker.class),
                new PipelineStats(registry),
                registry,
                new AtomicInteger(),
                new AtomicInteger()));

        channel.writeInbound(request("GET", "first"));
        channel.writeInbound(request("GET", "second"));

        firstBackend.complete(Unpooled.copiedBuffer("+FIRST\r\n", StandardCharsets.US_ASCII));
        channel.runPendingTasks();
        ByteBuf noneBeforeBatchFull = channel.readOutbound();
        assertThat(noneBeforeBatchFull).isNull();

        secondBackend.complete(Unpooled.copiedBuffer("+SECOND\r\n", StandardCharsets.US_ASCII));
        channel.runPendingTasks();

        ByteBuf first = channel.readOutbound();
        ByteBuf second = channel.readOutbound();
        assertThat(first.toString(StandardCharsets.US_ASCII)).isEqualTo("+FIRST\r\n");
        assertThat(second.toString(StandardCharsets.US_ASCII)).isEqualTo("+SECOND\r\n");
        first.release();
        second.release();
        assertThat(registry.summary("redis.proxy.pipeline.flush.batch.size").count()).isEqualTo(1);
        assertThat(registry.summary("redis.proxy.pipeline.flush.batch.size").totalAmount()).isEqualTo(2);
        channel.finishAndReleaseAll();
    }

    @Test
    void returnsResponsesInRequestOrderWhenBackendCompletesOutOfOrder() {
        ProxyProperties properties = properties(1024, 16, 0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RouteResolver routeResolver = new RouteResolver(properties, registry);
        BackendPool backendPool = mock(BackendPool.class);
        CompletableFuture<ByteBuf> firstBackend = new CompletableFuture<>();
        CompletableFuture<ByteBuf> secondBackend = new CompletableFuture<>();
        when(backendPool.doRequest(anyString(), any(ByteBuf.class), anyInt())).thenReturn(firstBackend, secondBackend);
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyChannelHandler(
                routeResolver,
                backendPool,
                mock(ClusterSlotRefresher.class),
                new NamespaceLimiter(registry),
                new KeyGovernanceLimiter(registry),
                mock(HotKeyTracker.class),
                mock(LargeKeyTracker.class),
                mock(SlowQueryTracker.class),
                new PipelineStats(registry),
                registry,
                new AtomicInteger(),
                new AtomicInteger()));

        channel.writeInbound(request("GET", "first"));
        channel.writeInbound(request("GET", "second"));

        secondBackend.complete(Unpooled.copiedBuffer("+SECOND\r\n", StandardCharsets.US_ASCII));
        channel.runPendingTasks();
        ByteBuf noneBeforeFirstCompletion = channel.readOutbound();
        assertThat(noneBeforeFirstCompletion).isNull();

        firstBackend.complete(Unpooled.copiedBuffer("+FIRST\r\n", StandardCharsets.US_ASCII));
        channel.runPendingTasks();

        ByteBuf first = channel.readOutbound();
        ByteBuf second = channel.readOutbound();
        assertThat(first.toString(StandardCharsets.US_ASCII)).isEqualTo("+FIRST\r\n");
        assertThat(second.toString(StandardCharsets.US_ASCII)).isEqualTo("+SECOND\r\n");
        first.release();
        second.release();
        ByteBuf noneAfterFlush = channel.readOutbound();
        assertThat(noneAfterFlush).isNull();
        channel.finishAndReleaseAll();
    }

    private static ProxyProperties properties(int maxPipelineDepth, int flushBatchSize, int flushMaxDelayMillis) {
        ProxyProperties properties = new ProxyProperties();
        properties.getLimits().setMaxPipelineDepth(maxPipelineDepth);
        properties.getLimits().setPipelineFlushBatchSize(flushBatchSize);
        properties.getLimits().setPipelineFlushMaxDelayMillis(flushMaxDelayMillis);
        properties.getRouting().setDefaultCluster("redis-a");
        ProxyProperties.Cluster cluster = new ProxyProperties.Cluster();
        cluster.setName("redis-a");
        cluster.setNodes(List.of("127.0.0.1:63790"));
        properties.getBackends().setClusters(List.of(cluster));
        properties.validate();
        return properties;
    }

    private static RespRequest request(String... args) {
        return new RespRequest(
                Unpooled.EMPTY_BUFFER,
                java.util.Arrays.stream(args)
                        .map(arg -> arg.getBytes(StandardCharsets.US_ASCII))
                        .toList());
    }
}
