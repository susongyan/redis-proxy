package analysis

import (
	"testing"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestSlowQueryTrackerTopKAndMultiKey(t *testing.T) {
	reg := metrics.NewRegistry()
	tracker := NewSlowQueryTracker(reg, config.SlowQueryAnalysisConfig{
		EndToEndThresholdMillis: 10,
		BackendThresholdMillis:  5,
		WindowSeconds:           60,
		BucketMillis:            1000,
		MaxTrackedKeys:          100,
		DebugTopN:               10,
	})
	ctx := tracker.Context("app-a", slowRequest("MGET", "app-a:1", "app-a:2"))
	tracker.Observe(ctx, 20*time.Millisecond, 8*time.Millisecond)

	entries := tracker.Snapshot(10)
	if len(entries) != 2 {
		t.Fatalf("entries=%+v", entries)
	}
	if entries[0].MaxEndToEndMillis != 20 || entries[0].MaxBackendMillis != 8 {
		t.Fatalf("entry=%+v", entries[0])
	}
	if got := testutil.ToFloat64(reg.SlowQueryObserved.WithLabelValues("app-a", "MGET", "both")); got != 2 {
		t.Fatalf("observed=%v want 2", got)
	}
}

func TestSlowQueryTrackerCapacityUnsupportedAndDisable(t *testing.T) {
	reg := metrics.NewRegistry()
	tracker := NewSlowQueryTracker(reg, config.SlowQueryAnalysisConfig{
		EndToEndThresholdMillis: 1,
		BackendThresholdMillis:  0,
		WindowSeconds:           60,
		BucketMillis:            1000,
		MaxTrackedKeys:          1,
		DebugTopN:               10,
	})
	tracker.Observe(tracker.Context("app-a", slowRequest("GET", "app-a:1")), 2*time.Millisecond, 0)
	tracker.Observe(tracker.Context("app-a", slowRequest("GET", "app-a:2")), 2*time.Millisecond, 0)
	if got := testutil.ToFloat64(reg.SlowQueryDropped.WithLabelValues("app-a", "GET")); got != 1 {
		t.Fatalf("dropped=%v want 1", got)
	}
	tracker.Observe(tracker.Context("app-a", slowRequest("SCAN", "0")), 2*time.Millisecond, 0)
	if got := testutil.ToFloat64(reg.SlowQueryUnsupported.WithLabelValues("SCAN")); got != 1 {
		t.Fatalf("unsupported=%v want 1", got)
	}
	disabled := false
	tracker.Configure(config.SlowQueryAnalysisConfig{
		Enabled:                 &disabled,
		EndToEndThresholdMillis: 1,
		BackendThresholdMillis:  1,
		WindowSeconds:           60,
		BucketMillis:            1000,
		MaxTrackedKeys:          1,
		DebugTopN:               10,
	})
	if entries := tracker.Snapshot(10); len(entries) != 0 {
		t.Fatalf("entries=%+v", entries)
	}
}

func TestSlowQueryTrackerSlidingWindowExpires(t *testing.T) {
	tracker := NewSlowQueryTracker(metrics.NewRegistry(), config.SlowQueryAnalysisConfig{
		EndToEndThresholdMillis: 1,
		BackendThresholdMillis:  1,
		WindowSeconds:           1,
		BucketMillis:            100,
		MaxTrackedKeys:          10,
		DebugTopN:               10,
	})
	now := time.UnixMilli(0)
	tracker.now = func() time.Time { return now }
	tracker.Observe(tracker.Context("app-a", slowRequest("GET", "app-a:1")), 2*time.Millisecond, 0)
	if entries := tracker.Snapshot(10); len(entries) != 1 {
		t.Fatalf("entries=%+v", entries)
	}
	now = now.Add(1200 * time.Millisecond)
	if entries := tracker.Snapshot(10); len(entries) != 0 {
		t.Fatalf("entries=%+v", entries)
	}
}

func slowRequest(args ...string) protocol.Request {
	values := make([][]byte, 0, len(args))
	for _, arg := range args {
		values = append(values, []byte(arg))
	}
	return protocol.Request{Args: values}
}
