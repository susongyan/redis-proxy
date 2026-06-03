package com.zuomagai.redisproxy.controlplane.api;

import com.zuomagai.redisproxy.controlplane.model.ProxyConfig;
import com.zuomagai.redisproxy.controlplane.model.PublishRequest;
import com.zuomagai.redisproxy.controlplane.model.RollbackRequest;
import com.zuomagai.redisproxy.controlplane.model.ConfigVersion;
import com.zuomagai.redisproxy.controlplane.model.ConfigDiff;
import com.zuomagai.redisproxy.controlplane.model.RouteStatus;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.RouteConvergence;
import com.zuomagai.redisproxy.controlplane.service.ConfigService;
import com.zuomagai.redisproxy.controlplane.service.ObservabilityService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
public class ConfigController {
    private final ConfigService configService;
    private final ObservabilityService observabilityService;

    public ConfigController(ConfigService configService, ObservabilityService observabilityService) {
        this.configService = configService;
        this.observabilityService = observabilityService;
    }

    @GetMapping("/healthz")
    public String healthz() {
        return "ok\n";
    }

    @GetMapping("/api/v1/config")
    public ProxyConfig getConfig() {
        return configService.get();
    }

    @GetMapping("/api/v1/config/watch")
    public DeferredResult<ResponseEntity<ProxyConfig>> watchConfig(
            @RequestParam("epoch") long routeEpoch,
            @RequestParam(value = "timeoutSeconds", defaultValue = "30") long timeoutSeconds) {
        long boundedTimeoutSeconds = Math.min(Math.max(1, timeoutSeconds), 60);
        DeferredResult<ResponseEntity<ProxyConfig>> result =
                new DeferredResult<>(Duration.ofSeconds(boundedTimeoutSeconds + 1).toMillis());
        result.onTimeout(() -> result.setResult(ResponseEntity.noContent().build()));
        configService.watch(routeEpoch, Duration.ofSeconds(boundedTimeoutSeconds))
                .whenComplete((config, error) -> {
                    if (error != null) {
                        result.setErrorResult(error);
                        return;
                    }
                    result.setResult(config
                            .map(ResponseEntity::ok)
                            .orElseGet(() -> ResponseEntity.noContent().build()));
                });
        return result;
    }

    @PutMapping(value = "/api/v1/config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProxyConfig updateConfig(@Valid @RequestBody ProxyConfig config) {
        return configService.update(config);
    }

    @PostMapping(value = "/api/v1/config/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConfigVersion publishConfig(@Valid @RequestBody PublishRequest request) {
        return configService.publish(request);
    }

    @PostMapping(value = "/api/v1/config/rollback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConfigVersion rollbackConfig(@Valid @RequestBody RollbackRequest request) {
        return configService.rollback(request);
    }

    @GetMapping("/api/v1/config/versions")
    public List<ConfigVersion> versions() {
        return configService.versions();
    }

    @GetMapping("/api/v1/config/versions/{versionId}")
    public ConfigVersion version(@PathVariable("versionId") long versionId) {
        return configService.version(versionId);
    }

    @GetMapping("/api/v1/config/diff")
    public ConfigDiff diff(@RequestParam("from") long fromVersionId, @RequestParam("to") long toVersionId) {
        return configService.diff(fromVersionId, toVersionId);
    }

    @GetMapping("/api/v1/routes/status")
    public RouteStatus routeStatus() {
        return configService.routeStatus();
    }

    @GetMapping("/api/v1/routes/convergence")
    public RouteConvergence routeConvergence() {
        return observabilityService.routeConvergence(configService.routeStatus());
    }
}
