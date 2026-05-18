package main

import (
	"context"
	"flag"
	"net/http"
	"os"
	"os/signal"
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

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	adminServer := admin.NewServer(cfg.Admin.Listen, cfg, reg)
	go func() {
		if err := adminServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("admin server", zap.Error(err))
		}
	}()

	server := proxy.NewServer(cfg, rt, pools, reg, log)
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
