package main

import (
	"testing"
	"time"
)

func TestControlPlaneWatchURLAppendsWatchEndpoint(t *testing.T) {
	got, err := controlPlaneWatchURL("http://127.0.0.1:8090/api/v1/config", 7, 30*time.Second)
	if err != nil {
		t.Fatalf("controlPlaneWatchURL returned error: %v", err)
	}
	want := "http://127.0.0.1:8090/api/v1/config/watch?epoch=7&timeoutSeconds=30"
	if got != want {
		t.Fatalf("watch url = %q, want %q", got, want)
	}
}

func TestControlPlaneWatchURLPreservesWatchEndpointAndQuery(t *testing.T) {
	got, err := controlPlaneWatchURL("http://127.0.0.1:8090/api/v1/config/watch?token=abc", 8, 1500*time.Millisecond)
	if err != nil {
		t.Fatalf("controlPlaneWatchURL returned error: %v", err)
	}
	want := "http://127.0.0.1:8090/api/v1/config/watch?epoch=8&timeoutSeconds=1&token=abc"
	if got != want {
		t.Fatalf("watch url = %q, want %q", got, want)
	}
}
