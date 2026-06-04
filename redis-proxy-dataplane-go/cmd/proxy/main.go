package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/admin"
	"github.com/example/redis-proxy-dataplane-go/internal/analysis"
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
	reg.LargeResponseThreshold.Set(float64(cfg.Limits.LargeResponseBytes))
	hotKeys := analysis.NewHotKeyTracker(reg, cfg.Analysis.HotKey)
	largeKeys := analysis.NewLargeKeyTracker(reg, cfg.Analysis.LargeKey)
	slowQueries := analysis.NewSlowQueryTracker(reg, cfg.Analysis.SlowQuery)
	manager, err := router.NewManager(cfg)
	if err != nil {
		log.Fatal("init route manager", zap.Error(err))
	}
	pools, err := backend.NewPools(cfg, reg, log)
	if err != nil {
		log.Fatal("init backend pools", zap.Error(err))
	}
	defer pools.Close()
	refreshClusterSlots(cfg, manager, pools, reg, log)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	triggerRefresh := startClusterSlotRefreshLoop(ctx, cfg, manager, pools, reg, log)
	startControlPlanePolling(ctx, cfg, manager, pools, reg, log, hotKeys, largeKeys, slowQueries)
	startControlPlaneRegistration(ctx, cfg, reg, log)

	adminServer := admin.NewServer(cfg.Admin.Listen, cfg, manager, pools, reg, hotKeys, largeKeys, slowQueries)
	go func() {
		if err := adminServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("admin server", zap.Error(err))
		}
	}()

	server := proxy.NewServer(cfg, manager, pools, reg, log, triggerRefresh, hotKeys, largeKeys, slowQueries)
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

type registrationPayload struct {
	ProxyID                   string `json:"proxyId"`
	Group                     string `json:"group"`
	AdvertiseIP               string `json:"advertiseIp"`
	AdvertisePort             int    `json:"advertisePort"`
	AdminURL                  string `json:"adminUrl"`
	Dataplane                 string `json:"dataplane"`
	Cluster                   string `json:"cluster"`
	PollIntervalSeconds       int    `json:"pollIntervalSeconds"`
	ServiceNamespace          string `json:"serviceNamespace"`
	ServiceName               string `json:"serviceName"`
	ServiceInstanceID         string `json:"serviceInstanceId"`
	DeploymentEnvironmentName string `json:"deploymentEnvironmentName"`
	RegistrationSource        string `json:"registrationSource"`
	HeartbeatTTLSeconds       int    `json:"heartbeatTtlSeconds"`
}

func startControlPlaneRegistration(ctx context.Context, cfg *config.Config, reg *metrics.Registry, log *zap.Logger) {
	if !cfg.Registration.Enabled {
		return
	}
	interval := time.Duration(cfg.Registration.HeartbeatIntervalSeconds) * time.Second
	if interval <= 0 {
		interval = 15 * time.Second
	}
	client := &http.Client{Timeout: 3 * time.Second}
	go func() {
		for {
			result := "success"
			if err := registerControlPlaneTarget(ctx, client, cfg); err != nil {
				result = "error"
				log.Warn("register control plane target", zap.Error(err))
			} else {
				reg.RegistrationTime.Set(float64(time.Now().Unix()))
			}
			reg.Registration.WithLabelValues(result).Inc()
			select {
			case <-ctx.Done():
				return
			case <-time.After(interval):
			}
		}
	}()
}

func registerControlPlaneTarget(ctx context.Context, client *http.Client, cfg *config.Config) error {
	endpoint, err := registrationEndpoint(cfg.Registration.ControlPlaneURL)
	if err != nil {
		return err
	}
	body, err := json.Marshal(registrationPayload{
		ProxyID:                   cfg.Instance.ProxyID,
		Group:                     cfg.Instance.Group,
		AdvertiseIP:               cfg.Instance.AdvertiseIP,
		AdvertisePort:             cfg.Instance.AdvertisePort,
		AdminURL:                  registrationAdminURL(cfg),
		Dataplane:                 defaultString(cfg.Registration.Dataplane, "go"),
		Cluster:                   defaultString(cfg.Registration.Cluster, cfg.Routing.DefaultCluster),
		PollIntervalSeconds:       defaultPositive(cfg.Registration.PollIntervalSeconds, 15),
		ServiceNamespace:          defaultString(cfg.Registration.ServiceNamespace, "redis-proxy"),
		ServiceName:               defaultString(cfg.Registration.ServiceName, "redis-proxy-dataplane"),
		ServiceInstanceID:         defaultString(cfg.Registration.ServiceInstanceID, cfg.Instance.ProxyID),
		DeploymentEnvironmentName: cfg.Registration.DeploymentEnvironmentName,
		RegistrationSource:        "dataplane",
		HeartbeatTTLSeconds:       defaultPositive(cfg.Registration.HeartbeatIntervalSeconds*3, 45),
	})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("control plane registration status %d", resp.StatusCode)
	}
	return nil
}

func registrationEndpoint(base string) (string, error) {
	parsed, err := url.Parse(base)
	if err != nil {
		return "", err
	}
	path := strings.TrimRight(parsed.Path, "/")
	if strings.HasSuffix(path, "/config") {
		path = strings.TrimSuffix(path, "/config")
	}
	if strings.HasSuffix(path, "/api/v1") {
		path += "/observability/targets"
	} else if !strings.HasSuffix(path, "/observability/targets") {
		path += "/api/v1/observability/targets"
	}
	parsed.Path = path
	return parsed.String(), nil
}

func registrationAdminURL(cfg *config.Config) string {
	if strings.TrimSpace(cfg.Registration.AdminURL) != "" {
		return strings.TrimSpace(cfg.Registration.AdminURL)
	}
	host, port, err := net.SplitHostPort(cfg.Admin.Listen)
	if err != nil {
		return "http://127.0.0.1:8080"
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}
	return "http://" + net.JoinHostPort(host, port)
}

func defaultString(value string, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return strings.TrimSpace(value)
}

func defaultPositive(value int, fallback int) int {
	if value <= 0 {
		return fallback
	}
	return value
}

func refreshClusterSlots(cfg *config.Config, rt *router.Manager, pools *backend.Pools, reg *metrics.Registry, log *zap.Logger) {
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

func startClusterSlotRefreshLoop(ctx context.Context, cfg *config.Config, rt *router.Manager, pools *backend.Pools, reg *metrics.Registry, log *zap.Logger) func() {
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

func observeRoutingState(cfg *config.Config, rt *router.Manager, reg *metrics.Registry) {
	reg.RouteEpoch.Set(float64(rt.CurrentEpoch()))
	if cfg.Mode == "cluster" {
		reg.SlotCoverage.Set(float64(rt.SlotCoverage()))
		reg.SlotRefreshTime.Set(float64(time.Now().Unix()))
	}
}

func clusterDegraded(rt *router.Manager, pools *backend.Pools) bool {
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

func startControlPlanePolling(ctx context.Context, cfg *config.Config, manager *router.Manager, pools *backend.Pools, reg *metrics.Registry, log *zap.Logger, hotKeys *analysis.HotKeyTracker, largeKeys *analysis.LargeKeyTracker, slowQueries *analysis.SlowQueryTracker) {
	if !cfg.ControlPlane.Enabled {
		return
	}
	retryDelay := time.Duration(cfg.ControlPlane.PollIntervalSeconds) * time.Second
	if retryDelay <= 0 {
		retryDelay = 5 * time.Second
	}
	watchTimeout := time.Duration(cfg.ControlPlane.WatchTimeoutSeconds) * time.Second
	if watchTimeout <= 0 {
		watchTimeout = 30 * time.Second
	}
	requestSlack := time.Duration(cfg.ControlPlane.RequestTimeoutMillis) * time.Millisecond
	if requestSlack <= 0 {
		requestSlack = time.Second
	}
	client := &http.Client{}
	go func() {
		for {
			if ctx.Err() != nil {
				return
			}
			retry := watchControlPlane(ctx, client, cfg, watchTimeout, requestSlack, manager, pools, reg, log, hotKeys, largeKeys, slowQueries)
			if retry {
				select {
				case <-ctx.Done():
					return
				case <-time.After(retryDelay):
				}
			}
		}
	}()
}

func watchControlPlane(ctx context.Context, client *http.Client, cfg *config.Config, watchTimeout time.Duration, requestSlack time.Duration, manager *router.Manager, pools *backend.Pools, reg *metrics.Registry, log *zap.Logger, hotKeys *analysis.HotKeyTracker, largeKeys *analysis.LargeKeyTracker, slowQueries *analysis.SlowQueryTracker) bool {
	manager.MarkPoll()
	watchURL, err := controlPlaneWatchURL(cfg.ControlPlane.URL, manager.CurrentEpoch(), watchTimeout, cfg.Instance.Group, cfg.Instance.ProxyID)
	if err != nil {
		reg.RouteSnapshotUpdates.WithLabelValues("error").Inc()
		log.Warn("build control plane watch url", zap.Error(err))
		return true
	}
	requestCtx, cancel := context.WithTimeout(ctx, watchTimeout+requestSlack)
	defer cancel()
	req, err := http.NewRequestWithContext(requestCtx, http.MethodGet, watchURL, nil)
	if err != nil {
		reg.RouteSnapshotUpdates.WithLabelValues("error").Inc()
		log.Warn("build control plane request", zap.Error(err))
		return true
	}
	resp, err := client.Do(req)
	if err != nil {
		reg.RouteSnapshotUpdates.WithLabelValues("error").Inc()
		log.Warn("watch control plane", zap.Error(err))
		return true
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNoContent {
		reg.RouteSnapshotUpdates.WithLabelValues("timeout").Inc()
		return false
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		reg.RouteSnapshotUpdates.WithLabelValues("error").Inc()
		log.Warn("watch control plane status", zap.Int("status", resp.StatusCode))
		return true
	}
	var next config.Config
	if err := json.NewDecoder(resp.Body).Decode(&next); err != nil {
		reg.RouteSnapshotUpdates.WithLabelValues("error").Inc()
		log.Warn("decode control plane config", zap.Error(err))
		return true
	}
	result, err := manager.ApplyConfig(&next, pools)
	if err != nil {
		if result == "" {
			result = "error"
		}
		reg.RouteSnapshotRejects.WithLabelValues(result).Inc()
		reg.RouteSnapshotUpdates.WithLabelValues("rejected").Inc()
		log.Warn("apply route snapshot", zap.String("result", result), zap.Error(err))
		return false
	}
	reg.RouteSnapshotUpdates.WithLabelValues("success").Inc()
	reg.RouteEpoch.Set(float64(manager.CurrentEpoch()))
	reg.LargeResponseThreshold.Set(float64(manager.Limits().LargeResponseBytes))
	hotKeys.Configure(next.Analysis.HotKey)
	largeKeys.Configure(next.Analysis.LargeKey)
	slowQueries.Configure(next.Analysis.SlowQuery)
	reg.RouteSnapshotTime.Set(float64(time.Now().Unix()))
	return false
}

func controlPlaneWatchURL(base string, epoch int64, watchTimeout time.Duration, group string, proxyID string) (string, error) {
	parsed, err := url.Parse(base)
	if err != nil {
		return "", err
	}
	if !strings.HasSuffix(parsed.Path, "/watch") {
		parsed.Path = strings.TrimRight(parsed.Path, "/") + "/watch"
	}
	timeoutSeconds := int64(watchTimeout / time.Second)
	if timeoutSeconds < 1 {
		timeoutSeconds = 1
	}
	query := parsed.Query()
	query.Set("epoch", strconv.FormatInt(epoch, 10))
	if strings.TrimSpace(group) != "" {
		query.Set("group", strings.TrimSpace(group))
	}
	if strings.TrimSpace(proxyID) != "" {
		query.Set("proxyId", strings.TrimSpace(proxyID))
	}
	query.Set("timeoutSeconds", strconv.FormatInt(timeoutSeconds, 10))
	parsed.RawQuery = query.Encode()
	return parsed.String(), nil
}
