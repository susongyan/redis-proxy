package com.example.redisproxy.dataplane.admin;

import com.example.redisproxy.dataplane.analysis.HotKeyTracker;
import com.example.redisproxy.dataplane.backend.BackendPool;
import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.router.RouteResolver;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {
    private final ProxyProperties properties;
    private final RouteResolver routeResolver;
    private final BackendPool backendPool;
    private final HotKeyTracker hotKeyTracker;

    public AdminController(ProxyProperties properties, RouteResolver routeResolver, BackendPool backendPool, HotKeyTracker hotKeyTracker) {
        this.properties = properties;
        this.routeResolver = routeResolver;
        this.backendPool = backendPool;
        this.hotKeyTracker = hotKeyTracker;
    }

    @GetMapping("/healthz")
    public String healthz() {
        return "ok\n";
    }

    @GetMapping("/readyz")
    public ResponseEntity<String> readyz() {
        if (!ready()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("not ready\n");
        }
        return ResponseEntity.ok("ready\n");
    }

    @GetMapping("/debug/config")
    public ProxyProperties config() {
        return properties;
    }

    @GetMapping("/debug/route-snapshot")
    public RouteResolver.SnapshotInfo routeSnapshot() {
        return routeResolver.snapshotInfo();
    }

    @GetMapping("/debug/hot-keys")
    public List<HotKeyTracker.Entry> hotKeys(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        return hotKeyTracker.snapshot(limit);
    }

    private boolean ready() {
        if (!"cluster".equals(properties.getMode())) {
            return !routeResolver.defaultNodes().isEmpty() && backendPool.hasActive(routeResolver.defaultNodes().getFirst());
        }
        for (String clusterName : routeResolver.routeClusters()) {
            if (routeResolver.clusterSlotCoverage(clusterName) != 16384) {
                return false;
            }
            for (String owner : routeResolver.clusterSlotOwners(clusterName)) {
                if (!backendPool.hasActive(owner)) {
                    return false;
                }
            }
        }
        return true;
    }
}
