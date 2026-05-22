package analysis

import (
	"sort"
	"strconv"
	"sync"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/governance"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
)

const (
	defaultMaxTrackedHotKeys = 10000
	defaultHotKeyMetricsTopN = 20
	defaultHotKeyWindow      = 60 * time.Second
	defaultHotKeyBucket      = time.Second
)

type HotKeyTracker struct {
	mu             sync.Mutex
	reg            *metrics.Registry
	enabled        bool
	maxTracked     int
	metricsTopN    int
	window         time.Duration
	bucket         time.Duration
	bucketCount    int
	counts         map[hotKey]*hotKeyWindow
	lastMetricKeys []hotKeyMetricLabel
	lastRefresh    time.Time
	now            func() time.Time
}

type hotKey struct {
	Namespace string `json:"namespace"`
	Command   string `json:"command"`
	Key       string `json:"key"`
}

type hotKeyMetricLabel struct {
	hotKey
	Rank string
}

type HotKeyEntry struct {
	Namespace string `json:"namespace"`
	Command   string `json:"command"`
	Key       string `json:"key"`
	Count     int64  `json:"count"`
}

type hotKeyWindow struct {
	buckets []hotKeyBucket
}

type hotKeyBucket struct {
	index int64
	count int64
}

func NewHotKeyTracker(reg *metrics.Registry, cfg config.HotKeyAnalysisConfig) *HotKeyTracker {
	t := &HotKeyTracker{
		reg:         reg,
		enabled:     true,
		maxTracked:  defaultMaxTrackedHotKeys,
		metricsTopN: defaultHotKeyMetricsTopN,
		window:      defaultHotKeyWindow,
		bucket:      defaultHotKeyBucket,
		bucketCount: int(defaultHotKeyWindow / defaultHotKeyBucket),
		counts:      map[hotKey]*hotKeyWindow{},
		now:         time.Now,
	}
	t.Configure(cfg)
	return t
}

func (t *HotKeyTracker) Configure(cfg config.HotKeyAnalysisConfig) {
	if t == nil {
		return
	}
	window := time.Duration(cfg.WindowSeconds) * time.Second
	bucket := time.Duration(cfg.BucketMillis) * time.Millisecond
	if window <= 0 {
		window = defaultHotKeyWindow
	}
	if bucket <= 0 {
		bucket = defaultHotKeyBucket
	}
	bucketCount := int(window / bucket)
	if bucketCount <= 0 {
		bucketCount = int(defaultHotKeyWindow / defaultHotKeyBucket)
	}
	maxTracked := cfg.MaxTrackedKeys
	if maxTracked <= 0 {
		maxTracked = defaultMaxTrackedHotKeys
	}
	metricsTopN := cfg.MetricsTopN
	if metricsTopN <= 0 {
		metricsTopN = defaultHotKeyMetricsTopN
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	changedWindow := t.bucketCount != bucketCount || t.bucket != bucket || t.window != window
	changedCapacity := t.maxTracked != maxTracked
	t.enabled = cfg.IsEnabled()
	t.maxTracked = maxTracked
	t.metricsTopN = metricsTopN
	t.window = window
	t.bucket = bucket
	t.bucketCount = bucketCount
	if changedWindow || changedCapacity || !t.enabled {
		t.clearLocked()
	}
}

func (t *HotKeyTracker) Observe(namespace string, req protocol.Request) {
	if t == nil {
		return
	}
	keys, supported := governance.Keys(req)
	if !supported || len(keys) == 0 {
		return
	}
	command := req.Command()
	t.mu.Lock()
	defer t.mu.Unlock()
	if !t.enabled {
		return
	}
	now := t.now()
	currentBucket := now.UnixNano() / t.bucket.Nanoseconds()
	for _, raw := range keys {
		item := hotKey{Namespace: namespace, Command: command, Key: string(raw)}
		window := t.counts[item]
		if window == nil && len(t.counts) >= t.maxTracked {
			t.reg.HotKeyDropped.WithLabelValues(namespace, command).Inc()
			continue
		}
		if window == nil {
			window = newHotKeyWindow(t.bucketCount)
			t.counts[item] = window
		}
		window.increment(currentBucket)
		t.reg.HotKeyObserved.WithLabelValues(namespace, command).Inc()
	}
	t.pruneLocked(currentBucket)
	t.reg.HotKeyTracked.Set(float64(len(t.counts)))
	if t.lastRefresh.IsZero() || now.Sub(t.lastRefresh) >= time.Second {
		t.refreshMetricsLocked(t.metricsTopN)
		t.lastRefresh = now
	}
}

func (t *HotKeyTracker) Snapshot(limit int) []HotKeyEntry {
	if t == nil {
		return nil
	}
	if limit <= 0 {
		limit = t.metricsTopN
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if !t.enabled {
		return nil
	}
	t.pruneLocked(t.now().UnixNano() / t.bucket.Nanoseconds())
	t.reg.HotKeyTracked.Set(float64(len(t.counts)))
	return t.topLocked(limit)
}

func (t *HotKeyTracker) clearLocked() {
	for _, label := range t.lastMetricKeys {
		t.reg.HotKeyTop.DeleteLabelValues(label.Namespace, label.Command, label.Key, label.Rank)
	}
	t.counts = map[hotKey]*hotKeyWindow{}
	t.lastMetricKeys = nil
	t.reg.HotKeyTracked.Set(0)
}

func (t *HotKeyTracker) refreshMetricsLocked(limit int) {
	for _, label := range t.lastMetricKeys {
		t.reg.HotKeyTop.DeleteLabelValues(label.Namespace, label.Command, label.Key, label.Rank)
	}
	top := t.topLocked(limit)
	t.lastMetricKeys = t.lastMetricKeys[:0]
	for i, entry := range top {
		rank := strconv.Itoa(i + 1)
		t.reg.HotKeyTop.WithLabelValues(entry.Namespace, entry.Command, entry.Key, rank).Set(float64(entry.Count))
		t.lastMetricKeys = append(t.lastMetricKeys, hotKeyMetricLabel{
			hotKey: hotKey{Namespace: entry.Namespace, Command: entry.Command, Key: entry.Key},
			Rank:   rank,
		})
	}
}

func (t *HotKeyTracker) pruneLocked(currentBucket int64) {
	for key, window := range t.counts {
		if window.total(currentBucket, t.bucketCount) == 0 {
			delete(t.counts, key)
		}
	}
}

func (t *HotKeyTracker) topLocked(limit int) []HotKeyEntry {
	entries := make([]HotKeyEntry, 0, len(t.counts))
	currentBucket := t.now().UnixNano() / t.bucket.Nanoseconds()
	for item, window := range t.counts {
		count := window.total(currentBucket, t.bucketCount)
		if count == 0 {
			continue
		}
		entries = append(entries, HotKeyEntry{
			Namespace: item.Namespace,
			Command:   item.Command,
			Key:       item.Key,
			Count:     count,
		})
	}
	sort.Slice(entries, func(i, j int) bool {
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

func newHotKeyWindow(bucketCount int) *hotKeyWindow {
	window := &hotKeyWindow{buckets: make([]hotKeyBucket, bucketCount)}
	for i := range window.buckets {
		window.buckets[i].index = -1
	}
	return window
}

func (w *hotKeyWindow) increment(bucket int64) {
	slot := int(bucket % int64(len(w.buckets)))
	if w.buckets[slot].index != bucket {
		w.buckets[slot] = hotKeyBucket{index: bucket}
	}
	w.buckets[slot].count++
}

func (w *hotKeyWindow) total(currentBucket int64, bucketCount int) int64 {
	var total int64
	for _, bucket := range w.buckets {
		if bucket.index >= 0 && currentBucket-bucket.index < int64(bucketCount) {
			total += bucket.count
		}
	}
	return total
}
