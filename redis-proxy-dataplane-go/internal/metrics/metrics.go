package metrics

import (
	"github.com/prometheus/client_golang/prometheus"
)

type Registry struct {
	Prom            *prometheus.Registry
	Requests        *prometheus.CounterVec
	Errors          *prometheus.CounterVec
	Latency         *prometheus.HistogramVec
	BackendLatency  *prometheus.HistogramVec
	ActiveConns     prometheus.Gauge
	BackendConns    prometheus.Gauge
	BackendInflight prometheus.Gauge
	ClientPending   prometheus.Gauge
	Moved           prometheus.Counter
	Ask             prometheus.Counter
}

func NewRegistry() *Registry {
	r := &Registry{Prom: prometheus.NewRegistry()}
	r.Requests = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_requests_total", Help: "Total proxied requests"}, []string{"command"})
	r.Errors = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_errors_total", Help: "Total proxy errors"}, []string{"type"})
	r.Latency = prometheus.NewHistogramVec(prometheus.HistogramOpts{Name: "redis_proxy_request_latency_seconds", Help: "Proxy request latency", Buckets: prometheus.DefBuckets}, []string{"command"})
	r.BackendLatency = prometheus.NewHistogramVec(prometheus.HistogramOpts{Name: "redis_proxy_backend_latency_seconds", Help: "Backend request latency", Buckets: prometheus.DefBuckets}, []string{"backend"})
	r.ActiveConns = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_active_connections", Help: "Active client connections"})
	r.BackendConns = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_backend_active_connections", Help: "Active backend connections"})
	r.BackendInflight = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_backend_inflight", Help: "Inflight backend requests"})
	r.ClientPending = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_client_pending_responses", Help: "Pending client responses"})
	r.Moved = prometheus.NewCounter(prometheus.CounterOpts{Name: "redis_proxy_moved_total", Help: "MOVED responses"})
	r.Ask = prometheus.NewCounter(prometheus.CounterOpts{Name: "redis_proxy_ask_total", Help: "ASK responses"})
	r.Prom.MustRegister(r.Requests, r.Errors, r.Latency, r.BackendLatency, r.ActiveConns, r.BackendConns, r.BackendInflight, r.ClientPending, r.Moved, r.Ask)
	return r
}
