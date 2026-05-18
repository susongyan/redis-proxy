package com.example.redisproxy.dataplane.admin;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {
    private final ProxyProperties properties;

    public AdminController(ProxyProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/healthz")
    public String healthz() {
        return "ok\n";
    }

    @GetMapping("/readyz")
    public String readyz() {
        return "ready\n";
    }

    @GetMapping("/debug/config")
    public ProxyProperties config() {
        return properties;
    }
}
