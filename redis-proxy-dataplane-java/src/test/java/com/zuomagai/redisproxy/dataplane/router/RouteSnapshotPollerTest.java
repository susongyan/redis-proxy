package com.zuomagai.redisproxy.dataplane.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RouteSnapshotPollerTest {
    @Test
    void appendsWatchEndpoint() {
        String url = RouteSnapshotPoller.controlPlaneWatchUrl(
                "http://127.0.0.1:8090/api/v1/config",
                7,
                Duration.ofSeconds(30));

        assertThat(url).isEqualTo("http://127.0.0.1:8090/api/v1/config/watch?epoch=7&timeoutSeconds=30");
    }

    @Test
    void preservesExistingWatchEndpointAndQuery() {
        String url = RouteSnapshotPoller.controlPlaneWatchUrl(
                "http://127.0.0.1:8090/api/v1/config/watch?token=abc",
                8,
                Duration.ofMillis(1500));

        assertThat(url).isEqualTo("http://127.0.0.1:8090/api/v1/config/watch?token=abc&epoch=8&timeoutSeconds=1");
    }
}
