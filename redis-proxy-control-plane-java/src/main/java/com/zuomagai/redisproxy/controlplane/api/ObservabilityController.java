package com.zuomagai.redisproxy.controlplane.api;

import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.HotKeyObservation;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.HistoryResponse;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.LargeKeyObservation;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.SlowQueryObservation;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.Summary;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityModels.TargetStatus;
import com.zuomagai.redisproxy.controlplane.model.observability.ObservabilityTarget;
import com.zuomagai.redisproxy.controlplane.service.ObservabilityService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ObservabilityController {
    private final ObservabilityService observabilityService;

    public ObservabilityController(ObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @PostMapping("/api/v1/observability/targets")
    public TargetStatus registerTarget(@RequestBody ObservabilityTarget target) {
        return observabilityService.register(target);
    }

    @GetMapping("/api/v1/observability/targets")
    public List<TargetStatus> targets() {
        return observabilityService.targets();
    }

    @DeleteMapping("/api/v1/observability/targets/{proxyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTarget(@PathVariable("proxyId") String proxyId) {
        observabilityService.delete(proxyId);
    }

    @GetMapping("/api/v1/observability/summary")
    public Summary summary() {
        return observabilityService.summary();
    }

    @GetMapping("/api/v1/observability/hot-keys")
    public List<HotKeyObservation> hotKeys(
            @RequestParam(value = "namespace", required = false) String namespace,
            @RequestParam(value = "command", required = false) String command,
            @RequestParam(value = "proxyId", required = false) String proxyId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return observabilityService.hotKeys(proxyId, namespace, command, limit);
    }

    @GetMapping("/api/v1/observability/large-keys")
    public List<LargeKeyObservation> largeKeys(
            @RequestParam(value = "namespace", required = false) String namespace,
            @RequestParam(value = "command", required = false) String command,
            @RequestParam(value = "proxyId", required = false) String proxyId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return observabilityService.largeKeys(proxyId, namespace, command, limit);
    }

    @GetMapping("/api/v1/observability/slow-queries")
    public List<SlowQueryObservation> slowQueries(
            @RequestParam(value = "namespace", required = false) String namespace,
            @RequestParam(value = "command", required = false) String command,
            @RequestParam(value = "proxyId", required = false) String proxyId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return observabilityService.slowQueries(proxyId, namespace, command, limit);
    }

    @GetMapping("/api/v1/observability/history")
    public HistoryResponse history(
            @RequestParam(value = "metric", required = false) String metric,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "stepSeconds", defaultValue = "60") int stepSeconds,
            @RequestParam(value = "proxyId", required = false) String proxyId,
            @RequestParam(value = "cluster", required = false) String cluster,
            @RequestParam(value = "dataplane", required = false) String dataplane) {
        return observabilityService.history(metric, from, to, stepSeconds, proxyId, cluster, dataplane);
    }

    @GetMapping(value = "/api/v1/observability/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
    public String prometheus() {
        return observabilityService.prometheus();
    }
}
