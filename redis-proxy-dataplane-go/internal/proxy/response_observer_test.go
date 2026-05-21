package proxy

import (
	"testing"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestObserveResponseSize(t *testing.T) {
	reg := metrics.NewRegistry()
	server := &Server{
		cfg:     &config.Config{Limits: config.LimitsConfig{LargeResponseBytes: 4}},
		metrics: reg,
	}

	server.observeResponseSize("GET", 3)
	server.observeResponseSize("GET", 4)

	if got := testutil.ToFloat64(reg.LargeResponses.WithLabelValues("GET")); got != 1 {
		t.Fatalf("large response count=%v want 1", got)
	}
}
