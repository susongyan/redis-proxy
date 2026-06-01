package com.zuomagai.redisproxy.dataplane.router;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.zuomagai.redisproxy.dataplane.backend.BackendPool;
import com.zuomagai.redisproxy.dataplane.config.ProxyProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterSlotRefresherTest {
    @Test
    void movedRefreshTriggerIsRateLimited() throws Exception {
        ProxyProperties properties = properties();
        RouteResolver routeResolver = mock(RouteResolver.class);
        BackendPool backendPool = mock(BackendPool.class);
        ClusterSlotRefresher refresher =
                new ClusterSlotRefresher(properties, routeResolver, backendPool, new SimpleMeterRegistry());
        try {
            refresher.start();

            refresher.triggerMovedRefresh();
            refresher.triggerMovedRefresh();

            verify(routeResolver, timeout(1000).times(2)).refreshSlots(eq(backendPool), any(Duration.class));
            Thread.sleep(250);
            verify(routeResolver, timeout(250).times(2)).refreshSlots(eq(backendPool), any(Duration.class));
        } finally {
            refresher.stop();
        }
    }

    private static ProxyProperties properties() {
        ProxyProperties properties = new ProxyProperties();
        properties.setMode("cluster");
        properties.getRouting().setDefaultCluster("redis-a");
        properties.getRouting().setClusterSlotsRefreshIntervalSeconds(60);
        ProxyProperties.Cluster cluster = new ProxyProperties.Cluster();
        cluster.setName("redis-a");
        cluster.setNodes(List.of("127.0.0.1:7100"));
        properties.getBackends().setClusters(List.of(cluster));
        return properties;
    }
}

