package com.example.redisproxy.dataplane.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteResolverTest {
    @Test
    void appliesClusterSlotsAndRoutesByCache() {
        RouteResolver resolver = resolver("127.0.0.1:7100", "127.0.0.1:7101");
        resolver.applyClusterSlots(Unpooled.copiedBuffer(
                "*2\r\n" +
                        "*3\r\n:0\r\n:8191\r\n*2\r\n$24\r\nredis-proxy-cluster-7100\r\n:7100\r\n" +
                        "*3\r\n:8192\r\n:16383\r\n*2\r\n$24\r\nredis-proxy-cluster-7101\r\n:7101\r\n",
                StandardCharsets.US_ASCII));

        assertThat(resolver.slotCoverage()).isEqualTo(16384);
        assertThat(resolver.route(requestForSlotAtLeast(8192))).isEqualTo("127.0.0.1:7101");
    }

    @Test
    void normalizesContainerHostnameByConfiguredPort() {
        RouteResolver resolver = resolver("127.0.0.1:7100", "127.0.0.1:7101");
        assertThat(resolver.normalizeAddr("redis-proxy-cluster-7101:7101")).isEqualTo("127.0.0.1:7101");
    }

    private static RouteResolver resolver(String... nodes) {
        ProxyProperties properties = new ProxyProperties();
        properties.setMode("cluster");
        ProxyProperties.Cluster cluster = new ProxyProperties.Cluster();
        cluster.setName("redis-a");
        cluster.setNodes(List.of(nodes));
        properties.getBackends().setClusters(List.of(cluster));
        properties.getRouting().setDefaultCluster("redis-a");
        return new RouteResolver(properties, new SimpleMeterRegistry());
    }

    private static RespRequest requestForSlotAtLeast(int minSlot) {
        for (int i = 0; i < 100_000; i++) {
            byte[] key = ("key-" + i).getBytes(StandardCharsets.US_ASCII);
            if (RedisSlot.slot(key) >= minSlot) {
                return new RespRequest(Unpooled.EMPTY_BUFFER, List.of("GET".getBytes(StandardCharsets.US_ASCII), key));
            }
        }
        throw new IllegalStateException("test key not found");
    }
}
