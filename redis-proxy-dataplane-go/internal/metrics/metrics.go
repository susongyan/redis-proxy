package metrics

import (
	"github.com/prometheus/client_golang/prometheus"
)

type Registry struct {
	Prom                  *prometheus.Registry
	Requests              *prometheus.CounterVec
	Errors                *prometheus.CounterVec
	Latency               *prometheus.HistogramVec
	BackendLatency        *prometheus.HistogramVec
	ActiveConns           prometheus.Gauge
	BackendConns          prometheus.Gauge
	BackendConnsByNode    *prometheus.GaugeVec
	BackendDesired        *prometheus.GaugeVec
	BackendInflight       prometheus.Gauge
	BackendInflightByNode *prometheus.GaugeVec
	ClientPending         prometheus.Gauge
	Moved                 prometheus.Counter
	Ask                   prometheus.Counter
	AskRedirects          *prometheus.CounterVec
	SlotCoverage          prometheus.Gauge
	RouteEpoch            prometheus.Gauge
	RouteDecisions        *prometheus.CounterVec
	RouteSnapshotUpdates  *prometheus.CounterVec
	RouteSnapshotRejects  *prometheus.CounterVec
	RouteSnapshotTime     prometheus.Gauge
	SlotRefreshes         *prometheus.CounterVec
	SlotRefreshTime       prometheus.Gauge
	BackendReconnects     *prometheus.CounterVec
	BackendReconnecting   *prometheus.GaugeVec
	BackendUnavailable    *prometheus.CounterVec
	Auth                  *prometheus.CounterVec
	GovernanceRejects     *prometheus.CounterVec
	GovernanceWarns       *prometheus.CounterVec
}

func NewRegistry() *Registry {
	r := &Registry{Prom: prometheus.NewRegistry()}
	r.Requests = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_requests_total", Help: "Total proxied requests"}, []string{"command"})
	r.Errors = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_errors_total", Help: "Total proxy errors"}, []string{"type"})
	r.Latency = prometheus.NewHistogramVec(prometheus.HistogramOpts{Name: "redis_proxy_request_latency_seconds", Help: "Proxy request latency", Buckets: prometheus.DefBuckets}, []string{"command"})
	r.BackendLatency = prometheus.NewHistogramVec(prometheus.HistogramOpts{Name: "redis_proxy_backend_latency_seconds", Help: "Backend request latency", Buckets: prometheus.DefBuckets}, []string{"backend"})
	r.ActiveConns = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_active_connections", Help: "Active client connections"})
	r.BackendConns = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_backend_active_connections", Help: "Active backend connections"})
	r.BackendConnsByNode = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_backend_active_connections_by_node", Help: "Active backend connections by Redis node"}, []string{"node"})
	r.BackendDesired = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_backend_desired_connections", Help: "Desired backend connections by Redis node"}, []string{"node"})
	r.BackendInflight = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_backend_inflight", Help: "Inflight backend requests"})
	r.BackendInflightByNode = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_backend_inflight_by_node", Help: "Inflight backend requests by Redis node"}, []string{"node"})
	r.ClientPending = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_client_pending_responses", Help: "Pending client responses"})
	r.Moved = prometheus.NewCounter(prometheus.CounterOpts{Name: "redis_proxy_moved_total", Help: "MOVED responses"})
	r.Ask = prometheus.NewCounter(prometheus.CounterOpts{Name: "redis_proxy_ask_total", Help: "ASK responses"})
	r.AskRedirects = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_ask_redirect_total", Help: "ASKING retry attempts by result"}, []string{"result"})
	r.SlotCoverage = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_cluster_slot_coverage", Help: "Number of Redis Cluster slots with cached backend mapping"})
	r.RouteEpoch = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_route_epoch", Help: "Current route epoch from local config"})
	r.RouteDecisions = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_route_decisions_total", Help: "Route decisions by cluster and rule"}, []string{"cluster", "rule"})
	r.RouteSnapshotUpdates = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_route_snapshot_update_total", Help: "Route snapshot update attempts by result"}, []string{"result"})
	r.RouteSnapshotRejects = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_route_snapshot_rejected_total", Help: "Rejected route snapshots by reason"}, []string{"reason"})
	r.RouteSnapshotTime = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_route_snapshot_last_success_timestamp_seconds", Help: "Unix timestamp of the last successful route snapshot update"})
	r.SlotRefreshes = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_cluster_slot_refresh_total", Help: "Cluster slot refresh attempts by result"}, []string{"result"})
	r.SlotRefreshTime = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_cluster_slot_last_refresh_timestamp_seconds", Help: "Unix timestamp of the last successful cluster slot refresh"})
	r.BackendReconnects = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_backend_reconnect_total", Help: "Backend reconnect attempts by Redis node and result"}, []string{"node", "result"})
	r.BackendReconnecting = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_backend_reconnecting", Help: "Backend reconnect attempts currently scheduled or running by Redis node"}, []string{"node"})
	r.BackendUnavailable = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_backend_unavailable_total", Help: "Backend unavailable errors by Redis node and reason"}, []string{"node", "reason"})
	r.Auth = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_auth_total", Help: "Proxy namespace auth attempts by result"}, []string{"namespace", "result"})
	r.GovernanceRejects = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_governance_reject_total", Help: "Requests rejected by proxy governance"}, []string{"namespace", "command", "reason"})
	r.GovernanceWarns = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_governance_warn_total", Help: "Requests matched warn-only proxy governance"}, []string{"namespace", "command", "reason"})
	r.Prom.MustRegister(
		prometheus.NewGoCollector(),
		prometheus.NewProcessCollector(prometheus.ProcessCollectorOpts{}),
		r.Requests,
		r.Errors,
		r.Latency,
		r.BackendLatency,
		r.ActiveConns,
		r.BackendConns,
		r.BackendConnsByNode,
		r.BackendDesired,
		r.BackendInflight,
		r.BackendInflightByNode,
		r.ClientPending,
		r.Moved,
		r.Ask,
		r.AskRedirects,
		r.SlotCoverage,
		r.RouteEpoch,
		r.RouteDecisions,
		r.RouteSnapshotUpdates,
		r.RouteSnapshotRejects,
		r.RouteSnapshotTime,
		r.SlotRefreshes,
		r.SlotRefreshTime,
		r.BackendReconnects,
		r.BackendReconnecting,
		r.BackendUnavailable,
		r.Auth,
		r.GovernanceRejects,
		r.GovernanceWarns,
	)
	return r
}
