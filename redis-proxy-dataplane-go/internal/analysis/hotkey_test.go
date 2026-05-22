package analysis

import (
	"testing"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestHotKeyTrackerTopK(t *testing.T) {
	tracker := NewHotKeyTracker(metrics.NewRegistry(), config.HotKeyAnalysisConfig{})
	tracker.now = func() time.Time { return time.Unix(10, 0) }
	tracker.Observe("app-a", request("GET", "key-1"))
	tracker.Observe("app-a", request("GET", "key-1"))
	tracker.Observe("app-a", request("GET", "key-2"))

	top := tracker.Snapshot(2)
	if len(top) != 2 {
		t.Fatalf("top len=%d want 2", len(top))
	}
	if top[0].Key != "key-1" || top[0].Count != 2 {
		t.Fatalf("top[0]=%+v", top[0])
	}
	if top[1].Key != "key-2" || top[1].Count != 1 {
		t.Fatalf("top[1]=%+v", top[1])
	}
}

func TestHotKeyTrackerMetricsAndCapacity(t *testing.T) {
	reg := metrics.NewRegistry()
	tracker := NewHotKeyTracker(reg, config.HotKeyAnalysisConfig{MaxTrackedKeys: 1})
	tracker.now = func() time.Time { return time.Unix(10, 0) }

	tracker.Observe("app-a", request("GET", "key-1"))
	tracker.Observe("app-a", request("GET", "key-2"))

	if got := testutil.ToFloat64(reg.HotKeyObserved.WithLabelValues("app-a", "GET")); got != 1 {
		t.Fatalf("observed=%v want 1", got)
	}
	if got := testutil.ToFloat64(reg.HotKeyDropped.WithLabelValues("app-a", "GET")); got != 1 {
		t.Fatalf("dropped=%v want 1", got)
	}
	if got := testutil.ToFloat64(reg.HotKeyTracked); got != 1 {
		t.Fatalf("tracked=%v want 1", got)
	}
	if got := testutil.ToFloat64(reg.HotKeyTop.WithLabelValues("app-a", "GET", "key-1", "1")); got != 1 {
		t.Fatalf("top metric=%v want 1", got)
	}
}

func TestHotKeyTrackerSlidingWindowExpiresOldCounts(t *testing.T) {
	reg := metrics.NewRegistry()
	tracker := NewHotKeyTracker(reg, config.HotKeyAnalysisConfig{WindowSeconds: 60, BucketMillis: 1000})
	now := time.Unix(0, 0)
	tracker.now = func() time.Time { return now }

	tracker.Observe("app-a", request("GET", "key-1"))
	now = now.Add(59 * time.Second)
	tracker.Observe("app-a", request("GET", "key-2"))

	top := tracker.Snapshot(10)
	if len(top) != 2 {
		t.Fatalf("top len before expiry=%d want 2: %+v", len(top), top)
	}

	now = now.Add(2 * time.Second)
	top = tracker.Snapshot(10)
	if len(top) != 1 || top[0].Key != "key-2" || top[0].Count != 1 {
		t.Fatalf("top after expiry=%+v", top)
	}
	if got := testutil.ToFloat64(reg.HotKeyTracked); got != 1 {
		t.Fatalf("tracked after expiry=%v want 1", got)
	}
}

func TestHotKeyTrackerCanBeDisabled(t *testing.T) {
	reg := metrics.NewRegistry()
	enabled := false
	tracker := NewHotKeyTracker(reg, config.HotKeyAnalysisConfig{Enabled: &enabled})
	tracker.now = func() time.Time { return time.Unix(10, 0) }

	tracker.Observe("app-a", request("GET", "key-1"))

	if top := tracker.Snapshot(10); len(top) != 0 {
		t.Fatalf("top len=%d want 0", len(top))
	}
}

func request(args ...string) protocol.Request {
	values := make([][]byte, 0, len(args))
	for _, arg := range args {
		values = append(values, []byte(arg))
	}
	return protocol.Request{Args: values}
}
