package analysis

import (
	"testing"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestLargeKeyTrackerTopKAndMultiKey(t *testing.T) {
	tracker := NewLargeKeyTracker(metrics.NewRegistry(), config.LargeKeyAnalysisConfig{
		RequestBytesThreshold:  1,
		ResponseBytesThreshold: 1,
		WindowSeconds:          300,
		BucketMillis:           1000,
		MaxTrackedKeys:         10,
		DebugTopN:              10,
	})
	tracker.now = func() time.Time { return time.Unix(10, 0) }
	ctx := tracker.Context("app-a", largeRequest("MGET", "key-1", "key-2"))

	tracker.ObserveRequest(ctx, 32)
	tracker.ObserveResponse(ctx, 128)

	top := tracker.Snapshot(10)
	if len(top) != 2 {
		t.Fatalf("top len=%d want 2: %+v", len(top), top)
	}
	for _, entry := range top {
		if entry.MaxRequestBytes != 32 || entry.MaxResponseBytes != 128 || entry.Count != 2 {
			t.Fatalf("entry=%+v", entry)
		}
	}
}

func TestLargeKeyTrackerCapacityUnsupportedAndDisable(t *testing.T) {
	reg := metrics.NewRegistry()
	tracker := NewLargeKeyTracker(reg, config.LargeKeyAnalysisConfig{
		RequestBytesThreshold:  1,
		ResponseBytesThreshold: 1,
		WindowSeconds:          300,
		BucketMillis:           1000,
		MaxTrackedKeys:         1,
		DebugTopN:              10,
	})
	tracker.now = func() time.Time { return time.Unix(10, 0) }

	tracker.ObserveResponse(tracker.Context("app-a", largeRequest("GET", "key-1")), 128)
	tracker.ObserveResponse(tracker.Context("app-a", largeRequest("GET", "key-2")), 128)
	tracker.ObserveResponse(tracker.Context("app-a", largeRequest("SCAN", "0")), 128)

	if got := testutil.ToFloat64(reg.LargeKeyDropped.WithLabelValues("app-a", "GET")); got != 1 {
		t.Fatalf("dropped=%v want 1", got)
	}
	if got := testutil.ToFloat64(reg.LargeKeyUnsupported.WithLabelValues("SCAN", "response")); got != 1 {
		t.Fatalf("unsupported=%v want 1", got)
	}
	disabled := false
	tracker.Configure(config.LargeKeyAnalysisConfig{
		Enabled:                &disabled,
		RequestBytesThreshold:  1,
		ResponseBytesThreshold: 1,
		WindowSeconds:          300,
		BucketMillis:           1000,
		MaxTrackedKeys:         10,
		DebugTopN:              10,
	})
	if top := tracker.Snapshot(10); len(top) != 0 {
		t.Fatalf("top len after disabled=%d want 0", len(top))
	}
}

func TestLargeKeyTrackerSlidingWindowExpires(t *testing.T) {
	reg := metrics.NewRegistry()
	tracker := NewLargeKeyTracker(reg, config.LargeKeyAnalysisConfig{
		RequestBytesThreshold:  1,
		ResponseBytesThreshold: 1,
		WindowSeconds:          60,
		BucketMillis:           1000,
		MaxTrackedKeys:         10,
		DebugTopN:              10,
	})
	now := time.Unix(0, 0)
	tracker.now = func() time.Time { return now }

	tracker.ObserveResponse(tracker.Context("app-a", largeRequest("GET", "key-1")), 128)
	now = now.Add(61 * time.Second)

	if top := tracker.Snapshot(10); len(top) != 0 {
		t.Fatalf("top after expiry=%+v", top)
	}
}

func largeRequest(args ...string) protocol.Request {
	values := make([][]byte, 0, len(args))
	for _, arg := range args {
		values = append(values, []byte(arg))
	}
	return protocol.Request{Args: values, Raw: []byte("raw")}
}
