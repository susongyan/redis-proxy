package com.example.redisproxy.dataplane.router;

import com.example.redisproxy.dataplane.backend.BackendPool;
import com.example.redisproxy.dataplane.analysis.HotKeyTracker;
import com.example.redisproxy.dataplane.analysis.LargeKeyTracker;
import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class RouteSnapshotPoller {
    private static final Logger log = LoggerFactory.getLogger(RouteSnapshotPoller.class);

    private final ProxyProperties properties;
    private final RouteResolver routeResolver;
    private final BackendPool backendPool;
    private final HotKeyTracker hotKeyTracker;
    private final LargeKeyTracker largeKeyTracker;
    private final ObjectMapper objectMapper;
    private final MeterRegistry registry;
    private final HttpClient client;
    private final AtomicLong lastSuccessTimestampSeconds = new AtomicLong();
    private volatile boolean stopped;
    private ScheduledExecutorService scheduler;

    public RouteSnapshotPoller(ProxyProperties properties, RouteResolver routeResolver, BackendPool backendPool, HotKeyTracker hotKeyTracker, LargeKeyTracker largeKeyTracker, ObjectMapper objectMapper, MeterRegistry registry) {
        this.properties = properties;
        this.routeResolver = routeResolver;
        this.backendPool = backendPool;
        this.hotKeyTracker = hotKeyTracker;
        this.largeKeyTracker = largeKeyTracker;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(requestTimeoutMillis()))
                .build();
        registry.gauge("redis.proxy.route.snapshot.last.success.timestamp.seconds", lastSuccessTimestampSeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.getControlPlane().isEnabled()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "route-snapshot-poller");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.execute(this::watchLoop);
    }

    void watchLoop() {
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            boolean retry = watchOnce();
            if (retry) {
                try {
                    Thread.sleep(Duration.ofSeconds(pollIntervalSeconds()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    boolean watchOnce() {
        try {
            Duration watchTimeout = Duration.ofSeconds(watchTimeoutSeconds());
            Duration requestTimeout = watchTimeout.plusMillis(requestTimeoutMillis());
            HttpRequest request = HttpRequest.newBuilder(URI.create(controlPlaneWatchUrl(routeResolver.currentEpoch(), watchTimeout)))
                    .timeout(requestTimeout)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204) {
                registry.counter("redis.proxy.route.snapshot.update", "result", "timeout").increment();
                return false;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                registry.counter("redis.proxy.route.snapshot.update", "result", "error").increment();
                log.warn("control plane returned status {}", response.statusCode());
                return true;
            }
            ProxyProperties next = objectMapper.readValue(response.body(), ProxyProperties.class);
            RouteResolver.ApplyResult result = routeResolver.applyConfig(next, backendPool);
            if (!result.applied()) {
                registry.counter("redis.proxy.route.snapshot.rejected", "reason", result.result()).increment();
                registry.counter("redis.proxy.route.snapshot.update", "result", "rejected").increment();
                log.warn("route snapshot rejected result={} error={}", result.result(), result.error());
                return false;
            }
            hotKeyTracker.configure(next.getAnalysis().getHotKey());
            largeKeyTracker.configure(next.getAnalysis().getLargeKey());
            registry.counter("redis.proxy.route.snapshot.update", "result", "success").increment();
            lastSuccessTimestampSeconds.set(System.currentTimeMillis() / 1000);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            registry.counter("redis.proxy.route.snapshot.update", "result", "error").increment();
            log.warn("watch route snapshot failed", e);
            return true;
        }
    }

    String controlPlaneWatchUrl(long routeEpoch, Duration watchTimeout) {
        return controlPlaneWatchUrl(properties.getControlPlane().getUrl(), routeEpoch, watchTimeout);
    }

    static String controlPlaneWatchUrl(String base, long routeEpoch, Duration watchTimeout) {
        String path = base;
        int queryIndex = base.indexOf('?');
        String basePath = queryIndex >= 0 ? base.substring(0, queryIndex) : base;
        String query = queryIndex >= 0 ? base.substring(queryIndex + 1) : "";
        if (!basePath.endsWith("/watch")) {
            basePath = stripTrailingSlashes(basePath) + "/watch";
        }
        path = query.isBlank() ? basePath : basePath + "?" + query;
        String separator = path.contains("?") ? "&" : "?";
        long timeoutSeconds = Math.max(1, watchTimeout.toSeconds());
        return path + separator
                + "epoch=" + URLEncoder.encode(Long.toString(routeEpoch), StandardCharsets.UTF_8)
                + "&timeoutSeconds=" + URLEncoder.encode(Long.toString(timeoutSeconds), StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private int pollIntervalSeconds() {
        return Math.max(1, properties.getControlPlane().getPollIntervalSeconds());
    }

    private int requestTimeoutMillis() {
        return Math.max(1, properties.getControlPlane().getRequestTimeoutMillis());
    }

    private int watchTimeoutSeconds() {
        return Math.max(1, properties.getControlPlane().getWatchTimeoutSeconds());
    }

    @PreDestroy
    public void stop() {
        stopped = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
