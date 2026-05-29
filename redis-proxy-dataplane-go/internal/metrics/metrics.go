package metrics

import (
	"sync/atomic"

	"github.com/prometheus/client_golang/prometheus"
)

type Registry struct {
	Prom                   *prometheus.Registry
	Requests               *prometheus.CounterVec
	Errors                 *prometheus.CounterVec
	Latency                *prometheus.HistogramVec
	ResponseBytes          *prometheus.HistogramVec
	LargeResponses         *prometheus.CounterVec
	LargeResponseThreshold prometheus.Gauge
	BackendLatency         *prometheus.HistogramVec
	ActiveConns            prometheus.Gauge
	BackendConns           prometheus.Gauge
	BackendConnsByNode     *prometheus.GaugeVec
	BackendDesired         *prometheus.GaugeVec
	BackendInflight        prometheus.Gauge
	BackendInflightByNode  *prometheus.GaugeVec
	ClientPending          prometheus.Gauge
	PipelineBuffered       prometheus.Gauge
	PipelineFlushBatch     prometheus.Histogram
	PipelineHOLBlocked     *prometheus.CounterVec
	PipelineHOLMaxWait     prometheus.Gauge
	Moved                  prometheus.Counter
	Ask                    prometheus.Counter
	AskRedirects           *prometheus.CounterVec
	SlotCoverage           prometheus.Gauge
	RouteEpoch             prometheus.Gauge
	RouteDecisions         *prometheus.CounterVec
	RouteSnapshotUpdates   *prometheus.CounterVec
	RouteSnapshotRejects   *prometheus.CounterVec
	RouteSnapshotTime      prometheus.Gauge
	SlotRefreshes          *prometheus.CounterVec
	SlotRefreshTime        prometheus.Gauge
	BackendReconnects      *prometheus.CounterVec
	BackendReconnecting    *prometheus.GaugeVec
	BackendUnavailable     *prometheus.CounterVec
	Auth                   *prometheus.CounterVec
	GovernanceRejects      *prometheus.CounterVec
	GovernanceWarns        *prometheus.CounterVec
	NamespaceConnections   *prometheus.GaugeVec
	NamespaceInflight      *prometheus.GaugeVec
	NamespaceLimitConfig   *prometheus.GaugeVec
	NamespaceLimitRejects  *prometheus.CounterVec
	KeyGovernanceRejects   *prometheus.CounterVec
	KeyGovernanceDecisions *prometheus.CounterVec
	KeyLimitConfig         *prometheus.GaugeVec
	KeyLimitWindowUsage    *prometheus.GaugeVec
	HotKeyObserved         *prometheus.CounterVec
	HotKeyDropped          *prometheus.CounterVec
	HotKeyTracked          prometheus.Gauge
	HotKeyTop              *prometheus.GaugeVec
	LargeKeyObserved       *prometheus.CounterVec
	LargeKeyDropped        *prometheus.CounterVec
	LargeKeyUnsupported    *prometheus.CounterVec
	LargeKeyTracked        prometheus.Gauge
	LargeKeyRequestThresh  prometheus.Gauge
	LargeKeyResponseThresh prometheus.Gauge
	SlowQueryObserved      *prometheus.CounterVec
	SlowQueryDropped       *prometheus.CounterVec
	SlowQueryUnsupported   *prometheus.CounterVec
	SlowQueryTracked       prometheus.Gauge
	SlowQueryE2EThresh     prometheus.Gauge
	SlowQueryBackendThresh prometheus.Gauge
	pipelineBuffered       atomic.Int64
	pipelineFlushes        atomic.Int64
	pipelineLastBatch      atomic.Int64
	pipelineMaxBatch       atomic.Int64
	pipelineHOLBlocked     atomic.Int64
	pipelineHOLMaxWaitMs   atomic.Int64
}

func NewRegistry() *Registry {
	r := &Registry{Prom: prometheus.NewRegistry()}
	r.Requests = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_requests_total", Help: "Total proxied requests"}, []string{"command"})
	r.Errors = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_errors_total", Help: "Total proxy errors"}, []string{"type"})
	r.Latency = prometheus.NewHistogramVec(prometheus.HistogramOpts{Name: "redis_proxy_request_latency_seconds", Help: "Proxy request latency", Buckets: prometheus.DefBuckets}, []string{"command"})
	r.ResponseBytes = prometheus.NewHistogramVec(prometheus.HistogramOpts{Name: "redis_proxy_response_bytes", Help: "Final response frame bytes by command", Buckets: []float64{64, 256, 1024, 4096, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024, 16 * 1024 * 1024, 64 * 1024 * 1024}}, []string{"command"})
	r.LargeResponses = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_large_response_total", Help: "Responses whose frame size exceeds configured large response threshold"}, []string{"command"})
	r.LargeResponseThreshold = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_large_response_threshold_bytes", Help: "Configured large response soft threshold in bytes"})
	r.BackendLatency = prometheus.NewHistogramVec(prometheus.HistogramOpts{Name: "redis_proxy_backend_latency_seconds", Help: "Backend request latency", Buckets: prometheus.DefBuckets}, []string{"backend"})
	r.ActiveConns = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_active_connections", Help: "Active client connections"})
	r.BackendConns = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_backend_active_connections", Help: "Active backend connections"})
	r.BackendConnsByNode = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_backend_active_connections_by_node", Help: "Active backend connections by Redis node"}, []string{"node"})
	r.BackendDesired = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_backend_desired_connections", Help: "Desired backend connections by Redis node"}, []string{"node"})
	r.BackendInflight = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_backend_inflight", Help: "Inflight backend requests"})
	r.BackendInflightByNode = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_backend_inflight_by_node", Help: "Inflight backend requests by Redis node"}, []string{"node"})
	r.ClientPending = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_client_pending_responses", Help: "Pending client responses"})
	r.PipelineBuffered = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_pipeline_buffered_responses", Help: "Client sequencer responses buffered behind a missing earlier sequence"})
	r.PipelineFlushBatch = prometheus.NewHistogram(prometheus.HistogramOpts{Name: "redis_proxy_pipeline_flush_batch_size", Help: "Number of responses flushed per client write batch", Buckets: []float64{1, 2, 4, 8, 16, 32, 64, 128}})
	r.PipelineHOLBlocked = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_pipeline_hol_blocked_total", Help: "Client responses blocked by an earlier missing sequence"}, []string{"reason"})
	r.PipelineHOLMaxWait = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_pipeline_hol_max_wait_millis", Help: "Maximum observed client sequencer wait in milliseconds"})
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
	r.NamespaceConnections = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_namespace_connections", Help: "Authenticated client connections by namespace"}, []string{"namespace"})
	r.NamespaceInflight = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_namespace_inflight", Help: "Inflight proxied requests by namespace"}, []string{"namespace"})
	r.NamespaceLimitConfig = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_namespace_limit_config", Help: "Configured namespace limits by namespace and limit type"}, []string{"namespace", "limit"})
	r.NamespaceLimitRejects = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_namespace_limit_reject_total", Help: "Requests or auth attempts rejected by namespace limits"}, []string{"namespace", "limit"})
	r.KeyGovernanceRejects = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_key_governance_reject_total", Help: "Requests rejected by key governance"}, []string{"namespace", "rule", "command", "reason"})
	r.KeyGovernanceDecisions = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_key_governance_decisions_total", Help: "Key governance rule decisions by namespace, rule and result"}, []string{"namespace", "rule", "command", "result", "reason"})
	r.KeyLimitConfig = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_key_limit_config", Help: "Configured key rule QPS limits by namespace and rule"}, []string{"namespace", "rule"})
	r.KeyLimitWindowUsage = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_key_limit_window_usage", Help: "Current key rule sliding window usage by namespace and rule"}, []string{"namespace", "rule"})
	r.HotKeyObserved = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_hot_key_observed_total", Help: "Keys observed by hot key analyzer"}, []string{"namespace", "command"})
	r.HotKeyDropped = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_hot_key_dropped_total", Help: "Keys skipped by hot key analyzer when tracking capacity is full"}, []string{"namespace", "command"})
	r.HotKeyTracked = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_hot_key_tracked_keys", Help: "Number of key entries currently tracked by hot key analyzer"})
	r.HotKeyTop = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "redis_proxy_hot_key_topk_count", Help: "Current hot key TopK counts by namespace, command, key and rank"}, []string{"namespace", "command", "key", "rank"})
	r.LargeKeyObserved = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_large_key_observed_total", Help: "Large key observations by namespace, command and direction"}, []string{"namespace", "command", "direction"})
	r.LargeKeyDropped = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_large_key_dropped_total", Help: "Large key observations dropped when tracking capacity is full"}, []string{"namespace", "command"})
	r.LargeKeyUnsupported = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_large_key_unsupported_total", Help: "Large request or response observations whose command key positions are unsupported"}, []string{"command", "direction"})
	r.LargeKeyTracked = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_large_key_tracked_keys", Help: "Number of key entries currently tracked by large key analyzer"})
	r.LargeKeyRequestThresh = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_large_key_request_threshold_bytes", Help: "Configured large key request threshold in bytes"})
	r.LargeKeyResponseThresh = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_large_key_response_threshold_bytes", Help: "Configured large key response threshold in bytes"})
	r.SlowQueryObserved = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_slow_query_observed_total", Help: "Slow query observations by namespace, command and trigger"}, []string{"namespace", "command", "trigger"})
	r.SlowQueryDropped = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_slow_query_dropped_total", Help: "Slow query observations dropped when tracking capacity is full"}, []string{"namespace", "command"})
	r.SlowQueryUnsupported = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "redis_proxy_slow_query_unsupported_total", Help: "Slow query observations whose command key positions are unsupported"}, []string{"command"})
	r.SlowQueryTracked = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_slow_query_tracked_keys", Help: "Number of key entries currently tracked by slow query analyzer"})
	r.SlowQueryE2EThresh = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_slow_query_end_to_end_threshold_millis", Help: "Configured slow query end-to-end threshold in milliseconds"})
	r.SlowQueryBackendThresh = prometheus.NewGauge(prometheus.GaugeOpts{Name: "redis_proxy_slow_query_backend_threshold_millis", Help: "Configured slow query backend threshold in milliseconds"})
	r.Prom.MustRegister(
		prometheus.NewGoCollector(),
		prometheus.NewProcessCollector(prometheus.ProcessCollectorOpts{}),
		r.Requests,
		r.Errors,
		r.Latency,
		r.ResponseBytes,
		r.LargeResponses,
		r.LargeResponseThreshold,
		r.BackendLatency,
		r.ActiveConns,
		r.BackendConns,
		r.BackendConnsByNode,
		r.BackendDesired,
		r.BackendInflight,
		r.BackendInflightByNode,
		r.ClientPending,
		r.PipelineBuffered,
		r.PipelineFlushBatch,
		r.PipelineHOLBlocked,
		r.PipelineHOLMaxWait,
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
		r.NamespaceConnections,
		r.NamespaceInflight,
		r.NamespaceLimitConfig,
		r.NamespaceLimitRejects,
		r.KeyGovernanceRejects,
		r.KeyGovernanceDecisions,
		r.KeyLimitConfig,
		r.KeyLimitWindowUsage,
		r.HotKeyObserved,
		r.HotKeyDropped,
		r.HotKeyTracked,
		r.HotKeyTop,
		r.LargeKeyObserved,
		r.LargeKeyDropped,
		r.LargeKeyUnsupported,
		r.LargeKeyTracked,
		r.LargeKeyRequestThresh,
		r.LargeKeyResponseThresh,
		r.SlowQueryObserved,
		r.SlowQueryDropped,
		r.SlowQueryUnsupported,
		r.SlowQueryTracked,
		r.SlowQueryE2EThresh,
		r.SlowQueryBackendThresh,
	)
	return r
}

type PipelineSnapshot struct {
	BufferedResponses  int64 `json:"bufferedResponses"`
	Flushes            int64 `json:"flushes"`
	LastFlushBatchSize int64 `json:"lastFlushBatchSize"`
	MaxFlushBatchSize  int64 `json:"maxFlushBatchSize"`
	HOLBlocked         int64 `json:"holBlocked"`
	HOLMaxWaitMillis   int64 `json:"holMaxWaitMillis"`
}

func (r *Registry) ObservePipelineBuffered(size int) {
	r.pipelineBuffered.Store(int64(size))
	r.PipelineBuffered.Set(float64(size))
}

func (r *Registry) ObservePipelineFlushBatch(size int) {
	if size <= 0 {
		return
	}
	value := int64(size)
	r.pipelineFlushes.Add(1)
	r.pipelineLastBatch.Store(value)
	for {
		current := r.pipelineMaxBatch.Load()
		if value <= current || r.pipelineMaxBatch.CompareAndSwap(current, value) {
			break
		}
	}
	r.PipelineFlushBatch.Observe(float64(size))
}

func (r *Registry) ObservePipelineHOLBlocked(reason string, buffered int) {
	if reason == "" {
		reason = "backend_pending"
	}
	r.pipelineHOLBlocked.Add(1)
	r.PipelineHOLBlocked.WithLabelValues(reason).Inc()
	r.ObservePipelineBuffered(buffered)
}

func (r *Registry) ObservePipelineHOLWait(waitMillis int64) {
	if waitMillis <= 0 {
		return
	}
	for {
		current := r.pipelineHOLMaxWaitMs.Load()
		if waitMillis <= current || r.pipelineHOLMaxWaitMs.CompareAndSwap(current, waitMillis) {
			break
		}
	}
	r.PipelineHOLMaxWait.Set(float64(r.pipelineHOLMaxWaitMs.Load()))
}

func (r *Registry) PipelineSnapshot() PipelineSnapshot {
	return PipelineSnapshot{
		BufferedResponses:  r.pipelineBuffered.Load(),
		Flushes:            r.pipelineFlushes.Load(),
		LastFlushBatchSize: r.pipelineLastBatch.Load(),
		MaxFlushBatchSize:  r.pipelineMaxBatch.Load(),
		HOLBlocked:         r.pipelineHOLBlocked.Load(),
		HOLMaxWaitMillis:   r.pipelineHOLMaxWaitMs.Load(),
	}
}
