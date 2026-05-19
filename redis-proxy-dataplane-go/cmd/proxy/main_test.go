package main

import (
	"testing"
	"time"
)

func TestRefreshLimiterThrottlesMovedRefresh(t *testing.T) {
	now := time.Unix(100, 0)
	limiter := newRefreshLimiter(2 * time.Second)
	limiter.now = func() time.Time {
		return now
	}

	if !limiter.Allow() {
		t.Fatal("first MOVED refresh trigger should be allowed")
	}
	if limiter.Allow() {
		t.Fatal("second MOVED refresh trigger inside throttle window should be dropped")
	}

	now = now.Add(2 * time.Second)
	if !limiter.Allow() {
		t.Fatal("MOVED refresh trigger should be allowed after throttle window")
	}
}
