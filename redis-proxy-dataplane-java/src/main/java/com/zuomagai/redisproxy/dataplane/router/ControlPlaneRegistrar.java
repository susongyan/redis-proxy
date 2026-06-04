package com.zuomagai.redisproxy.dataplane.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zuomagai.redisproxy.dataplane.config.ProxyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ControlPlaneRegistrar {
    private static final Logger log = LoggerFactory.getLogger(ControlPlaneRegistrar.class);

    private final ProxyProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry registry;
    private final HttpClient client;
    private final AtomicLong lastSuccessTimestampSeconds = new AtomicLong();
    private ScheduledExecutorService scheduler;

    public ControlPlaneRegistrar(ProxyProperties properties, ObjectMapper objectMapper, MeterRegistry registry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        registry.gauge("redis.proxy.control.plane.registration.last.success.timestamp.seconds", lastSuccessTimestampSeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.getRegistration().isEnabled()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "control-plane-registrar");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::registerSafely, 0, heartbeatIntervalSeconds(), TimeUnit.SECONDS);
    }

    void registerSafely() {
        try {
            registerOnce();
            registry.counter("redis.proxy.control.plane.registration", "result", "success").increment();
            lastSuccessTimestampSeconds.set(System.currentTimeMillis() / 1000);
        } catch (Exception error) {
            registry.counter("redis.proxy.control.plane.registration", "result", "error").increment();
            log.warn("register control plane target failed", error);
        }
    }

    void registerOnce() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(registrationEndpoint(properties.getRegistration().getControlPlaneUrl())))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload())))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("control plane registration status " + response.statusCode());
        }
    }

    Map<String, Object> payload() {
        ProxyProperties.Registration registration = properties.getRegistration();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proxyId", properties.getInstance().getProxyId());
        payload.put("group", properties.getInstance().getGroup());
        payload.put("advertiseIp", properties.getInstance().getAdvertiseIp());
        payload.put("advertisePort", properties.getInstance().getAdvertisePort());
        payload.put("adminUrl", adminUrl());
        payload.put("dataplane", blank(registration.getDataplane()) ? "java" : registration.getDataplane());
        payload.put("cluster", blank(registration.getCluster()) ? properties.getRouting().getDefaultCluster() : registration.getCluster());
        payload.put("pollIntervalSeconds", registration.getPollIntervalSeconds() <= 0 ? 15 : registration.getPollIntervalSeconds());
        payload.put("serviceNamespace", blank(registration.getServiceNamespace()) ? "redis-proxy" : registration.getServiceNamespace());
        payload.put("serviceName", blank(registration.getServiceName()) ? "redis-proxy-dataplane" : registration.getServiceName());
        payload.put("serviceInstanceId", blank(registration.getServiceInstanceId()) ? properties.getInstance().getProxyId() : registration.getServiceInstanceId());
        payload.put("deploymentEnvironmentName", registration.getDeploymentEnvironmentName());
        payload.put("registrationSource", "dataplane");
        payload.put("heartbeatTtlSeconds", Math.max(45, heartbeatIntervalSeconds() * 3));
        return payload;
    }

    String adminUrl() {
        String configured = properties.getRegistration().getAdminUrl();
        if (!blank(configured)) {
            return configured.trim();
        }
        String listen = properties.getAdmin().getListen();
        int colon = listen.lastIndexOf(':');
        String host = colon > 0 ? listen.substring(0, colon) : "127.0.0.1";
        String port = colon > 0 ? listen.substring(colon + 1) : "8080";
        if (host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host)) {
            host = "127.0.0.1";
        }
        return "http://" + host + ":" + port;
    }

    static String registrationEndpoint(String base) {
        String trimmed = base == null ? "" : base.trim();
        String path = trimmed;
        String query = "";
        int queryIndex = trimmed.indexOf('?');
        if (queryIndex >= 0) {
            path = trimmed.substring(0, queryIndex);
            query = trimmed.substring(queryIndex + 1);
        }
        path = stripTrailingSlashes(path);
        if (path.endsWith("/config")) {
            path = path.substring(0, path.length() - "/config".length());
        }
        if (path.endsWith("/api/v1")) {
            path = path + "/observability/targets";
        } else if (!path.endsWith("/observability/targets")) {
            path = path + "/api/v1/observability/targets";
        }
        return query.isBlank() ? path : path + "?" + query;
    }

    private int heartbeatIntervalSeconds() {
        return Math.max(1, properties.getRegistration().getHeartbeatIntervalSeconds());
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
