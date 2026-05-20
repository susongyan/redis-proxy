package com.example.redisproxy.controlplane.api;

import com.example.redisproxy.controlplane.model.ProxyConfig;
import com.example.redisproxy.controlplane.service.ConfigService;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
public class ConfigController {
    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
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
}
