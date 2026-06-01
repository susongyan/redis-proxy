package com.zuomagai.redisproxy.controlplane.model.observability;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {
    private Storage storage = new Storage();

    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }

    public static class Storage {
        private String type = "memory";
        private int retentionSeconds = 21_600;
        private int maxSnapshotsPerProxy = 720;
        private Otlp otlp = new Otlp();
        private Prometheus prometheus = new Prometheus();
        private Influx influx = new Influx();
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public int getRetentionSeconds() { return retentionSeconds; }
        public void setRetentionSeconds(int retentionSeconds) { this.retentionSeconds = retentionSeconds; }
        public int getMaxSnapshotsPerProxy() { return maxSnapshotsPerProxy; }
        public void setMaxSnapshotsPerProxy(int maxSnapshotsPerProxy) { this.maxSnapshotsPerProxy = maxSnapshotsPerProxy; }
        public Otlp getOtlp() { return otlp; }
        public void setOtlp(Otlp otlp) { this.otlp = otlp; }
        public Prometheus getPrometheus() { return prometheus; }
        public void setPrometheus(Prometheus prometheus) { this.prometheus = prometheus; }
        public Influx getInflux() { return influx; }
        public void setInflux(Influx influx) { this.influx = influx; }
    }

    public static class Otlp {
        private String endpoint = "http://127.0.0.1:4318";
        private Map<String, String> headers = new HashMap<>();
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    }

    public static class Prometheus {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Influx {
        private String url = "http://127.0.0.1:8086";
        private String org = "redis-proxy";
        private String bucket = "observability";
        private String token = "";
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getOrg() { return org; }
        public void setOrg(String org) { this.org = org; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }
}
