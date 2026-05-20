package main

import (
	"context"
	"flag"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/admin"
	"github.com/example/redis-proxy-dataplane-go/internal/backend"
	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/proxy"
	"github.com/example/redis-proxy-dataplane-go/internal/router"
	"go.uber.org/zap"
)

func main() {
	configPath := flag.String("config", "configs/proxy.yaml", "path to proxy config")
	flag.Parse()

	log, _ := zap.NewProduction()
	defer log.Sync()

	cfg, err := config.Load(*configPath)
	if err != nil {
		log.Fatal("load config", zap.Error(err))
	}

	reg := metrics.NewRegistry()
	rt, err := router.New(cfg)
	if err != nil {
		log.Fatal("init router", zap.Error(err))
	}
	pools, err := backend.NewPools(cfg, reg, log)
	if err != nil {
		log.Fatal("init backend pools", zap.Error(err))
	}
	defer pools.Close()
	refreshClusterSlots(cfg, rt, pools, reg, log)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	triggerRefresh := startClusterSlotRefreshLoop(ctx, cfg, rt, pools, reg, log)

	adminServer := admin.NewServer(cfg.Admin.Listen, cfg, rt, pools, reg)
	go func() {
		if err := adminServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("admin server", zap.Error(err))
		}
	}()

	server := proxy.NewServer(cfg, rt, pools, reg, log, triggerRefresh)
	go func() {
		if err := server.ListenAndServe(ctx); err != nil {
			log.Fatal("proxy server", zap.Error(err))
		}
	}()

	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	server.Shutdown()
	_ = adminServer.Shutdown(shutdownCtx)
}

func refreshClusterSlots(cfg *config.Config, rt *router.Router, pools *backend.Pools, reg *metrics.Registry, log *zap.Logger) {
	observeRoutingState(cfg, rt, reg)
	if cfg.Mode != "cluster" {
		return
	}
	if err := rt.RefreshSlots(pools); err != nil {
		reg.SlotRefreshes.WithLabelValues("error").Inc()
		log.Warn("refresh cluster slots", zap.Error(err))
		return
	}
	observeRoutingState(cfg, rt, reg)
	reg.SlotRefreshes.WithLabelValues("success").Inc()
}

func startClusterSlotRefreshLoop(ctx context.Context, cfg *config.Config, rt *router.Router, pools *backend.Pools, reg *metrics.Registry, log *zap.Logger) func() {
	trigger := make(chan struct{}, 1)
	if cfg.Mode != "cluster" || cfg.Routing.ClusterSlotsRefreshIntervalSeconds <= 0 {
		return func() {}
	}
	interval := time.Duration(cfg.Routing.ClusterSlotsRefreshIntervalSeconds) * time.Second
	limiter := newRefreshLimiter(2 * time.Second)
	go func() {
		for {
			next := interval
			if clusterDegraded(rt, pools) {
				next = 5 * time.Second
			}
			timer := time.NewTimer(next)
			select {
			case <-ctx.Done():
				timer.Stop()
				return
			case <-timer.C:
				refreshClusterSlots(cfg, rt, pools, reg, log)
			case <-trigger:
				timer.Stop()
				if limiter.Allow() {
					refreshClusterSlots(cfg, rt, pools, reg, log)
				}
			}
		}
	}()
	return func() {
		select {
		case trigger <- struct{}{}:
		default:
		}
	}
}

type refreshLimiter struct {
	mu       sync.Mutex
	interval time.Duration
	last     time.Time
	now      func() time.Time
}

func newRefreshLimiter(interval time.Duration) *refreshLimiter {
	return &refreshLimiter{interval: interval, now: time.Now}
}

func (l *refreshLimiter) Allow() bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	if !l.last.IsZero() && now.Sub(l.last) < l.interval {
		return false
	}
	l.last = now
	return true
}

func observeRoutingState(cfg *config.Config, rt *router.Router, reg *metrics.Registry) {
	reg.RouteEpoch.Set(float64(cfg.Routing.RouteEpoch))
	if cfg.Mode == "cluster" {
		reg.SlotCoverage.Set(float64(rt.SlotCoverage()))
		reg.SlotRefreshTime.Set(float64(time.Now().Unix()))
	}
}

func clusterDegraded(rt *router.Router, pools *backend.Pools) bool {
	for _, clusterName := range rt.RouteClusters() {
		if rt.ClusterSlotCoverage(clusterName) != router.Slots {
			return true
		}
		for _, owner := range rt.ClusterSlotOwners(clusterName) {
			if !pools.HasActive(owner) {
				return true
			}
		}
	}
	return false
}
