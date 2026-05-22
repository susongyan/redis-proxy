package analysis

import (
	"sort"
	"sync"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/governance"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
)

const (
	defaultLargeKeyWindow      = 300 * time.Second
	defaultLargeKeyBucket      = time.Second
	defaultMaxTrackedLargeKeys = 10000
	defaultLargeKeyDebugTopN   = 100
)

type LargeKeyTracker struct {
	mu                sync.Mutex
	reg               *metrics.Registry
	enabled           bool
	requestThreshold  int
	responseThreshold int
	window            time.Duration
	bucket            time.Duration
	bucketCount       int
	maxTracked        int
	debugTopN         int
	counts            map[largeKey]*largeKeyWindow
	now               func() time.Time
}

type largeKey struct {
	Namespace string
	Command   string
	Key       string
}

type LargeKeyContext struct {
	Namespace string
	Command   string
	Keys      []string
	Supported bool
}

type LargeKeyEntry struct {
	Namespace        string `json:"namespace"`
	Command          string `json:"command"`
	Key              string `json:"key"`
	Count            int64  `json:"count"`
	MaxRequestBytes  int    `json:"maxRequestBytes"`
	MaxResponseBytes int    `json:"maxResponseBytes"`
	LastSeenUnix     int64  `json:"lastSeenUnix"`
}

type largeKeyWindow struct {
	buckets []largeKeyBucket
}

type largeKeyBucket struct {
	index            int64
	count            int64
	maxRequestBytes  int
	maxResponseBytes int
	lastSeenUnix     int64
}

func NewLargeKeyTracker(reg *metrics.Registry, cfg config.LargeKeyAnalysisConfig) *LargeKeyTracker {
	t := &LargeKeyTracker{
		reg:         reg,
		enabled:     true,
		window:      defaultLargeKeyWindow,
		bucket:      defaultLargeKeyBucket,
		bucketCount: int(defaultLargeKeyWindow / defaultLargeKeyBucket),
		maxTracked:  defaultMaxTrackedLargeKeys,
		debugTopN:   defaultLargeKeyDebugTopN,
		counts:      map[largeKey]*largeKeyWindow{},
		now:         time.Now,
	}
	t.Configure(cfg)
	return t
}

func (t *LargeKeyTracker) Configure(cfg config.LargeKeyAnalysisConfig) {
	if t == nil {
		return
	}
	window := time.Duration(cfg.WindowSeconds) * time.Second
	bucket := time.Duration(cfg.BucketMillis) * time.Millisecond
	if window <= 0 {
		window = defaultLargeKeyWindow
	}
	if bucket <= 0 {
		bucket = defaultLargeKeyBucket
	}
	bucketCount := int(window / bucket)
	if bucketCount <= 0 {
		bucketCount = int(defaultLargeKeyWindow / defaultLargeKeyBucket)
	}
	maxTracked := cfg.MaxTrackedKeys
	if maxTracked <= 0 {
		maxTracked = defaultMaxTrackedLargeKeys
	}
	debugTopN := cfg.DebugTopN
	if debugTopN <= 0 {
		debugTopN = defaultLargeKeyDebugTopN
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	changedWindow := t.bucketCount != bucketCount || t.bucket != bucket || t.window != window
	changedCapacity := t.maxTracked != maxTracked
	t.enabled = cfg.IsEnabled()
	t.requestThreshold = cfg.RequestBytesThreshold
	t.responseThreshold = cfg.ResponseBytesThreshold
	t.window = window
	t.bucket = bucket
	t.bucketCount = bucketCount
	t.maxTracked = maxTracked
	t.debugTopN = debugTopN
	t.reg.LargeKeyRequestThresh.Set(float64(t.requestThreshold))
	t.reg.LargeKeyResponseThresh.Set(float64(t.responseThreshold))
	if changedWindow || changedCapacity || !t.enabled {
		t.counts = map[largeKey]*largeKeyWindow{}
		t.reg.LargeKeyTracked.Set(0)
	}
}

func (t *LargeKeyTracker) Context(namespace string, req protocol.Request) LargeKeyContext {
	keys, supported := governance.Keys(req)
	ctx := LargeKeyContext{Namespace: namespace, Command: req.Command(), Supported: supported}
	if supported {
		ctx.Keys = make([]string, 0, len(keys))
		for _, key := range keys {
			ctx.Keys = append(ctx.Keys, string(key))
		}
	}
	return ctx
}

func (t *LargeKeyTracker) ObserveRequest(ctx LargeKeyContext, size int) {
	t.observe(ctx, size, 0, "request")
}

func (t *LargeKeyTracker) ObserveResponse(ctx LargeKeyContext, size int) {
	t.observe(ctx, 0, size, "response")
}

func (t *LargeKeyTracker) Snapshot(limit int) []LargeKeyEntry {
	if t == nil {
		return nil
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if limit <= 0 {
		limit = t.debugTopN
	}
	if !t.enabled {
		return nil
	}
	currentBucket := t.now().UnixNano() / t.bucket.Nanoseconds()
	t.pruneLocked(currentBucket)
	t.reg.LargeKeyTracked.Set(float64(len(t.counts)))
	return t.topLocked(currentBucket, limit)
}

func (t *LargeKeyTracker) observe(ctx LargeKeyContext, requestBytes int, responseBytes int, direction string) {
	if t == nil {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if !t.enabled {
		return
	}
	if direction == "request" && (t.requestThreshold <= 0 || requestBytes < t.requestThreshold) {
		return
	}
	if direction == "response" && (t.responseThreshold <= 0 || responseBytes < t.responseThreshold) {
		return
	}
	if !ctx.Supported || len(ctx.Keys) == 0 {
		t.reg.LargeKeyUnsupported.WithLabelValues(ctx.Command, direction).Inc()
		return
	}
	now := t.now()
	currentBucket := now.UnixNano() / t.bucket.Nanoseconds()
	for _, raw := range ctx.Keys {
		item := largeKey{Namespace: ctx.Namespace, Command: ctx.Command, Key: raw}
		window := t.counts[item]
		if window == nil && len(t.counts) >= t.maxTracked {
			t.reg.LargeKeyDropped.WithLabelValues(ctx.Namespace, ctx.Command).Inc()
			continue
		}
		if window == nil {
			window = newLargeKeyWindow(t.bucketCount)
			t.counts[item] = window
		}
		window.observe(currentBucket, requestBytes, responseBytes, now.Unix())
		t.reg.LargeKeyObserved.WithLabelValues(ctx.Namespace, ctx.Command, direction).Inc()
	}
	t.pruneLocked(currentBucket)
	t.reg.LargeKeyTracked.Set(float64(len(t.counts)))
}

func (t *LargeKeyTracker) pruneLocked(currentBucket int64) {
	for key, window := range t.counts {
		entry := window.total(currentBucket, t.bucketCount)
		if entry.Count == 0 {
			delete(t.counts, key)
		}
	}
}

func (t *LargeKeyTracker) topLocked(currentBucket int64, limit int) []LargeKeyEntry {
	entries := make([]LargeKeyEntry, 0, len(t.counts))
	for item, window := range t.counts {
		entry := window.total(currentBucket, t.bucketCount)
		if entry.Count == 0 {
			continue
		}
		entry.Namespace = item.Namespace
		entry.Command = item.Command
		entry.Key = item.Key
		entries = append(entries, entry)
	}
	sort.Slice(entries, func(i, j int) bool {
		left := max(entries[i].MaxRequestBytes, entries[i].MaxResponseBytes)
		right := max(entries[j].MaxRequestBytes, entries[j].MaxResponseBytes)
		if left != right {
			return left > right
		}
		if entries[i].Count != entries[j].Count {
			return entries[i].Count > entries[j].Count
		}
		if entries[i].Namespace != entries[j].Namespace {
			return entries[i].Namespace < entries[j].Namespace
		}
		if entries[i].Command != entries[j].Command {
			return entries[i].Command < entries[j].Command
		}
		return entries[i].Key < entries[j].Key
	})
	if len(entries) > limit {
		entries = entries[:limit]
	}
	return entries
}

func newLargeKeyWindow(bucketCount int) *largeKeyWindow {
	window := &largeKeyWindow{buckets: make([]largeKeyBucket, bucketCount)}
	for i := range window.buckets {
		window.buckets[i].index = -1
	}
	return window
}

func (w *largeKeyWindow) observe(bucket int64, requestBytes int, responseBytes int, lastSeenUnix int64) {
	slot := int(bucket % int64(len(w.buckets)))
	if w.buckets[slot].index != bucket {
		w.buckets[slot] = largeKeyBucket{index: bucket}
	}
	w.buckets[slot].count++
	w.buckets[slot].maxRequestBytes = max(w.buckets[slot].maxRequestBytes, requestBytes)
	w.buckets[slot].maxResponseBytes = max(w.buckets[slot].maxResponseBytes, responseBytes)
	w.buckets[slot].lastSeenUnix = max(w.buckets[slot].lastSeenUnix, lastSeenUnix)
}

func (w *largeKeyWindow) total(currentBucket int64, bucketCount int) LargeKeyEntry {
	var entry LargeKeyEntry
	for _, bucket := range w.buckets {
		if bucket.index >= 0 && currentBucket-bucket.index < int64(bucketCount) {
			entry.Count += bucket.count
			entry.MaxRequestBytes = max(entry.MaxRequestBytes, bucket.maxRequestBytes)
			entry.MaxResponseBytes = max(entry.MaxResponseBytes, bucket.maxResponseBytes)
			entry.LastSeenUnix = max(entry.LastSeenUnix, bucket.lastSeenUnix)
		}
	}
	return entry
}
