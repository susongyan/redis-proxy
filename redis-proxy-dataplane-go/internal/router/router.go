package router

import (
	"fmt"

	"github.com/example/redis-proxy-dataplane-go/internal/config"
	"github.com/example/redis-proxy-dataplane-go/internal/protocol"
)

const Slots = 16384

type Router struct {
	mode    string
	cluster config.ClusterConfig
}

func New(cfg *config.Config) (*Router, error) {
	for _, cluster := range cfg.Backends.Clusters {
		if cluster.Name == cfg.Routing.DefaultCluster {
			return &Router{mode: cfg.Mode, cluster: cluster}, nil
		}
	}
	return nil, fmt.Errorf("default cluster %q not found", cfg.Routing.DefaultCluster)
}

func (r *Router) Route(req protocol.Request) (string, error) {
	if len(r.cluster.Nodes) == 1 || r.mode == "standalone" {
		return r.cluster.Nodes[0], nil
	}
	key, ok := ExtractKey(req.Args)
	if !ok {
		return r.cluster.Nodes[0], nil
	}
	slot := Slot(key)
	return r.cluster.Nodes[slot%len(r.cluster.Nodes)], nil
}

func ExtractKey(args [][]byte) ([]byte, bool) {
	if len(args) < 2 {
		return nil, false
	}
	return hashTag(args[1]), true
}

func hashTag(key []byte) []byte {
	start := -1
	for i, b := range key {
		if b == '{' {
			start = i
			break
		}
	}
	if start < 0 {
		return key
	}
	for i := start + 1; i < len(key); i++ {
		if key[i] == '}' {
			if i == start+1 {
				return key
			}
			return key[start+1 : i]
		}
	}
	return key
}

func Slot(key []byte) int {
	return int(crc16(hashTag(key)) % Slots)
}

func crc16(data []byte) uint16 {
	var crc uint16
	for _, b := range data {
		crc ^= uint16(b) << 8
		for i := 0; i < 8; i++ {
			if crc&0x8000 != 0 {
				crc = (crc << 1) ^ 0x1021
			} else {
				crc <<= 1
			}
		}
	}
	return crc
}
