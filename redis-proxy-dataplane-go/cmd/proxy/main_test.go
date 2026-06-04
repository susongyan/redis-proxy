package main

import (
	"context"
	"encoding/json"
	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestControlPlaneWatchURLAppendsWatchEndpoint(t *testing.T) {
	got, err := controlPlaneWatchURL("http://127.0.0.1:8090/api/v1/config", 7, 30*time.Second, "frontend", "frontend-127-0-0-1-6379")
	if err != nil {
		t.Fatalf("controlPlaneWatchURL returned error: %v", err)
	}
	want := "http://127.0.0.1:8090/api/v1/config/watch?epoch=7&group=frontend&proxyId=frontend-127-0-0-1-6379&timeoutSeconds=30"
	if got != want {
		t.Fatalf("watch url = %q, want %q", got, want)
	}
}

func TestControlPlaneWatchURLPreservesWatchEndpointAndQuery(t *testing.T) {
	got, err := controlPlaneWatchURL("http://127.0.0.1:8090/api/v1/config/watch?token=abc", 8, 1500*time.Millisecond, "payment", "payment-127-0-0-1-6381")
	if err != nil {
		t.Fatalf("controlPlaneWatchURL returned error: %v", err)
	}
	want := "http://127.0.0.1:8090/api/v1/config/watch?epoch=8&group=payment&proxyId=payment-127-0-0-1-6381&timeoutSeconds=1&token=abc"
	if got != want {
		t.Fatalf("watch url = %q, want %q", got, want)
	}
}

func TestRegistrationEndpointFromConfigURL(t *testing.T) {
	got, err := registrationEndpoint("http://127.0.0.1:8090/api/v1/config")
	if err != nil {
		t.Fatalf("registrationEndpoint returned error: %v", err)
	}
	want := "http://127.0.0.1:8090/api/v1/observability/targets"
	if got != want {
		t.Fatalf("registration endpoint = %q, want %q", got, want)
	}
}

func TestRegistrationAdminURLDefaultsWildcardHost(t *testing.T) {
	cfg := &config.Config{Admin: config.AdminConfig{Listen: "0.0.0.0:18080"}}
	got := registrationAdminURL(cfg)
	want := "http://127.0.0.1:18080"
	if got != want {
		t.Fatalf("registration admin url = %q, want %q", got, want)
	}
}

func TestRegisterControlPlaneTargetIncludesInstanceFields(t *testing.T) {
	var payload registrationPayload
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	cfg := &config.Config{
		Instance: config.InstanceConfig{
			ProxyID:       "frontend-10-0-0-1-6379",
			Group:         "frontend",
			AdvertiseIP:   "10.0.0.1",
			AdvertisePort: 6379,
		},
		Admin:   config.AdminConfig{Listen: "0.0.0.0:8080"},
		Routing: config.RoutingConfig{DefaultCluster: "redis-a"},
		Registration: config.RegistrationConfig{
			ControlPlaneURL: server.URL + "/api/v1",
			Dataplane:       "go",
		},
	}
	if err := registerControlPlaneTarget(context.Background(), server.Client(), cfg); err != nil {
		t.Fatal(err)
	}
	if payload.ProxyID != "frontend-10-0-0-1-6379" || payload.Group != "frontend" || payload.AdvertiseIP != "10.0.0.1" || payload.AdvertisePort != 6379 {
		t.Fatalf("payload=%+v", payload)
	}
}
