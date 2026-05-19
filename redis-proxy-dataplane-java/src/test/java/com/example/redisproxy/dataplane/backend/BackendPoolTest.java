package com.example.redisproxy.dataplane.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class BackendPoolTest {
    @Test
    void reconnectingMetricsDoNotBecomeNegative() throws Exception {
        BackendPool pool = new BackendPool(new ProxyProperties(), new SimpleMeterRegistry());
        try {
            Method decrement = BackendPool.class.getDeclaredMethod("decrementReconnecting", String.class);
            decrement.setAccessible(true);

            decrement.invoke(pool, "127.0.0.1:7100");
            decrement.invoke(pool, "127.0.0.1:7100");

            assertThat(pool.reconnectingCount()).isZero();
            assertThat(pool.nodeReconnectingCount("127.0.0.1:7100")).isZero();
        } finally {
            pool.close();
        }
    }
}

