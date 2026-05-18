package com.example.redisproxy.controlplane.api;

import com.example.redisproxy.controlplane.model.ProxyConfig;
import com.example.redisproxy.controlplane.service.ConfigService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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

    @PutMapping(value = "/api/v1/config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProxyConfig updateConfig(@Valid @RequestBody ProxyConfig config) {
        return configService.update(config);
    }
}
