package analysis

import (
	"sort"
	"strconv"
	"sync"
	"time"

	"github.com/example/redis-proxy-dataplane-go/internal/governance"
	"github.com/example/redis-proxy-dataplane-go/internal/metrics"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
)

const (
	defaultMaxTrackedHotKeys = 10000
	defaultHotKeyMetricsTopN = 20
)

type HotKeyTracker struct {
	mu             sync.Mutex
	reg            *metrics.Registry
	maxTracked     int
	metricsTopN    int
	counts         map[hotKey]int64
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

func NewHotKeyTracker(reg *metrics.Registry) *HotKeyTracker {
	return &HotKeyTracker{
		reg:         reg,
		maxTracked:  defaultMaxTrackedHotKeys,
		metricsTopN: defaultHotKeyMetricsTopN,
		counts:      map[hotKey]int64{},
		now:         time.Now,
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
	for _, raw := range keys {
		item := hotKey{Namespace: namespace, Command: command, Key: string(raw)}
		if _, ok := t.counts[item]; !ok && len(t.counts) >= t.maxTracked {
			t.reg.HotKeyDropped.WithLabelValues(namespace, command).Inc()
			continue
		}
		t.counts[item]++
		t.reg.HotKeyObserved.WithLabelValues(namespace, command).Inc()
	}
	t.reg.HotKeyTracked.Set(float64(len(t.counts)))
	if now := t.now(); t.lastRefresh.IsZero() || now.Sub(t.lastRefresh) >= time.Second {
		t.refreshMetricsLocked(defaultHotKeyMetricsTopN)
		t.lastRefresh = now
	}
}

func (t *HotKeyTracker) Snapshot(limit int) []HotKeyEntry {
	if t == nil {
		return nil
	}
	if limit <= 0 {
		limit = defaultHotKeyMetricsTopN
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.topLocked(limit)
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

func (t *HotKeyTracker) topLocked(limit int) []HotKeyEntry {
	entries := make([]HotKeyEntry, 0, len(t.counts))
	for item, count := range t.counts {
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
