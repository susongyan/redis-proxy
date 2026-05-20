package admin

import (
	"encoding/json"
	"net/http"

	"github.com/example/redis-proxy-dataplane-go/internal/backend"
	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/router"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

func NewServer(addr string, cfg *config.Config, rt *router.Manager, pools *backend.Pools, reg *metrics.Registry) *http.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})
	mux.HandleFunc("/readyz", func(w http.ResponseWriter, _ *http.Request) {
		if !ready(cfg, rt, pools) {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte("not ready\n"))
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ready\n"))
	})
	mux.HandleFunc("/debug/config", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(cfg)
	})
	mux.HandleFunc("/debug/route-snapshot", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(rt.SnapshotInfo())
	})
	mux.Handle("/metrics", promhttp.HandlerFor(reg.Prom, promhttp.HandlerOpts{}))
	return &http.Server{Addr: addr, Handler: mux}
}

func ready(cfg *config.Config, rt *router.Manager, pools *backend.Pools) bool {
	if cfg.Mode != "cluster" {
		nodes := rt.DefaultNodes()
		return len(nodes) > 0 && pools.HasActive(nodes[0])
	}
	for _, clusterName := range rt.RouteClusters() {
		if rt.ClusterSlotCoverage(clusterName) != router.Slots {
			return false
		}
		for _, owner := range rt.ClusterSlotOwners(clusterName) {
			if !pools.HasActive(owner) {
				return false
			}
		}
	}
	return true
}
