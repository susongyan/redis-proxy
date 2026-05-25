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
	defaultSlowQueryWindow     = 300 * time.Second
	defaultSlowQueryBucket     = time.Second
	defaultMaxTrackedSlowQuery = 10000
	defaultSlowQueryDebugTopN  = 100
	defaultSlowQueryE2EMillis  = 100
	defaultSlowQueryBackendMS  = 50
)

type SlowQueryTracker struct {
	mu               sync.Mutex
	reg              *metrics.Registry
	enabled          bool
	e2eThreshold     int
	backendThreshold int
	window           time.Duration
	bucket           time.Duration
	bucketCount      int
	maxTracked       int
	debugTopN        int
	counts           map[slowQueryKey]*slowQueryWindow
	now              func() time.Time
}

type slowQueryKey struct {
	Namespace string
	Command   string
	Key       string
}

type SlowQueryContext struct {
	Namespace string
	Command   string
	Keys      []string
	Supported bool
}

type SlowQueryEntry struct {
	Namespace         string `json:"namespace"`
	Command           string `json:"command"`
	Key               string `json:"key"`
	Count             int64  `json:"count"`
	MaxEndToEndMillis int64  `json:"maxEndToEndMillis"`
	MaxBackendMillis  int64  `json:"maxBackendMillis"`
	LastSeenUnix      int64  `json:"lastSeenUnix"`
}

type slowQueryWindow struct {
	buckets []slowQueryBucket
}

type slowQueryBucket struct {
	index             int64
	count             int64
	maxEndToEndMillis int64
	maxBackendMillis  int64
	lastSeenUnix      int64
}

func NewSlowQueryTracker(reg *metrics.Registry, cfg config.SlowQueryAnalysisConfig) *SlowQueryTracker {
	t := &SlowQueryTracker{
		reg:              reg,
		enabled:          true,
		e2eThreshold:     defaultSlowQueryE2EMillis,
		backendThreshold: defaultSlowQueryBackendMS,
		window:           defaultSlowQueryWindow,
		bucket:           defaultSlowQueryBucket,
		bucketCount:      int(defaultSlowQueryWindow / defaultSlowQueryBucket),
		maxTracked:       defaultMaxTrackedSlowQuery,
		debugTopN:        defaultSlowQueryDebugTopN,
		counts:           map[slowQueryKey]*slowQueryWindow{},
		now:              time.Now,
	}
	t.Configure(cfg)
	return t
}

func (t *SlowQueryTracker) Configure(cfg config.SlowQueryAnalysisConfig) {
	if t == nil {
		return
	}
	window := time.Duration(cfg.WindowSeconds) * time.Second
	bucket := time.Duration(cfg.BucketMillis) * time.Millisecond
	if window <= 0 {
		window = defaultSlowQueryWindow
	}
	if bucket <= 0 {
		bucket = defaultSlowQueryBucket
	}
	bucketCount := int(window / bucket)
	if bucketCount <= 0 {
		bucketCount = int(defaultSlowQueryWindow / defaultSlowQueryBucket)
	}
	maxTracked := cfg.MaxTrackedKeys
	if maxTracked <= 0 {
		maxTracked = defaultMaxTrackedSlowQuery
	}
	debugTopN := cfg.DebugTopN
	if debugTopN <= 0 {
		debugTopN = defaultSlowQueryDebugTopN
	}
	e2eThreshold := cfg.EndToEndThresholdMillis
	if e2eThreshold == 0 {
		e2eThreshold = defaultSlowQueryE2EMillis
	}
	backendThreshold := cfg.BackendThresholdMillis
	if backendThreshold == 0 {
		backendThreshold = defaultSlowQueryBackendMS
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	changedWindow := t.bucketCount != bucketCount || t.bucket != bucket || t.window != window
	changedCapacity := t.maxTracked != maxTracked
	t.enabled = cfg.IsEnabled()
	t.e2eThreshold = e2eThreshold
	t.backendThreshold = backendThreshold
	t.window = window
	t.bucket = bucket
	t.bucketCount = bucketCount
	t.maxTracked = maxTracked
	t.debugTopN = debugTopN
	t.reg.SlowQueryE2EThresh.Set(float64(t.e2eThreshold))
	t.reg.SlowQueryBackendThresh.Set(float64(t.backendThreshold))
	if changedWindow || changedCapacity || !t.enabled {
		t.counts = map[slowQueryKey]*slowQueryWindow{}
		t.reg.SlowQueryTracked.Set(0)
	}
}

func (t *SlowQueryTracker) Context(namespace string, req protocol.Request) SlowQueryContext {
	keys, supported := governance.Keys(req)
	ctx := SlowQueryContext{Namespace: namespace, Command: req.Command(), Supported: supported}
	if supported {
		ctx.Keys = make([]string, 0, len(keys))
		for _, key := range keys {
			ctx.Keys = append(ctx.Keys, string(key))
		}
	}
	return ctx
}

func (t *SlowQueryTracker) Observe(ctx SlowQueryContext, endToEnd time.Duration, backend time.Duration) {
	if t == nil {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if !t.enabled {
		return
	}
	e2eMillis := endToEnd.Milliseconds()
	backendMillis := backend.Milliseconds()
	e2eHit := t.e2eThreshold > 0 && e2eMillis >= int64(t.e2eThreshold)
	backendHit := t.backendThreshold > 0 && backendMillis >= int64(t.backendThreshold)
	if !e2eHit && !backendHit {
		return
	}
	if !ctx.Supported || len(ctx.Keys) == 0 {
		t.reg.SlowQueryUnsupported.WithLabelValues(ctx.Command).Inc()
		return
	}
	now := t.now()
	currentBucket := now.UnixNano() / t.bucket.Nanoseconds()
	trigger := slowQueryTrigger(e2eHit, backendHit)
	for _, raw := range ctx.Keys {
		item := slowQueryKey{Namespace: ctx.Namespace, Command: ctx.Command, Key: raw}
		window := t.counts[item]
		if window == nil && len(t.counts) >= t.maxTracked {
			t.reg.SlowQueryDropped.WithLabelValues(ctx.Namespace, ctx.Command).Inc()
			continue
		}
		if window == nil {
			window = newSlowQueryWindow(t.bucketCount)
			t.counts[item] = window
		}
		window.observe(currentBucket, e2eMillis, backendMillis, now.Unix())
		t.reg.SlowQueryObserved.WithLabelValues(ctx.Namespace, ctx.Command, trigger).Inc()
	}
	t.pruneLocked(currentBucket)
	t.reg.SlowQueryTracked.Set(float64(len(t.counts)))
}

func (t *SlowQueryTracker) Snapshot(limit int) []SlowQueryEntry {
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
	t.reg.SlowQueryTracked.Set(float64(len(t.counts)))
	return t.topLocked(currentBucket, limit)
}

func (t *SlowQueryTracker) pruneLocked(currentBucket int64) {
	for key, window := range t.counts {
		entry := window.total(currentBucket, t.bucketCount)
		if entry.Count == 0 {
			delete(t.counts, key)
		}
	}
}

func (t *SlowQueryTracker) topLocked(currentBucket int64, limit int) []SlowQueryEntry {
	entries := make([]SlowQueryEntry, 0, len(t.counts))
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
		left := max64(entries[i].MaxEndToEndMillis, entries[i].MaxBackendMillis)
		right := max64(entries[j].MaxEndToEndMillis, entries[j].MaxBackendMillis)
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

func newSlowQueryWindow(bucketCount int) *slowQueryWindow {
	window := &slowQueryWindow{buckets: make([]slowQueryBucket, bucketCount)}
	for i := range window.buckets {
		window.buckets[i].index = -1
	}
	return window
}

func (w *slowQueryWindow) observe(bucket int64, e2eMillis int64, backendMillis int64, lastSeenUnix int64) {
	slot := int(bucket % int64(len(w.buckets)))
	if w.buckets[slot].index != bucket {
		w.buckets[slot] = slowQueryBucket{index: bucket}
	}
	w.buckets[slot].count++
	w.buckets[slot].maxEndToEndMillis = max64(w.buckets[slot].maxEndToEndMillis, e2eMillis)
	w.buckets[slot].maxBackendMillis = max64(w.buckets[slot].maxBackendMillis, backendMillis)
	w.buckets[slot].lastSeenUnix = max64(w.buckets[slot].lastSeenUnix, lastSeenUnix)
}

func (w *slowQueryWindow) total(currentBucket int64, bucketCount int) SlowQueryEntry {
	var entry SlowQueryEntry
	for _, bucket := range w.buckets {
		if bucket.index >= 0 && currentBucket-bucket.index < int64(bucketCount) {
			entry.Count += bucket.count
			entry.MaxEndToEndMillis = max64(entry.MaxEndToEndMillis, bucket.maxEndToEndMillis)
			entry.MaxBackendMillis = max64(entry.MaxBackendMillis, bucket.maxBackendMillis)
			entry.LastSeenUnix = max64(entry.LastSeenUnix, bucket.lastSeenUnix)
		}
	}
	return entry
}

func slowQueryTrigger(e2eHit bool, backendHit bool) string {
	if e2eHit && backendHit {
		return "both"
	}
	if e2eHit {
		return "end_to_end"
	}
	return "backend"
}

func max64(a int64, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
