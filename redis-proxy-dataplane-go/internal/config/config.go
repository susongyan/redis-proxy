package config

import (
	"errors"
	"fmt"
	"os"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Server   ServerConfig  `yaml:"server"`
	Admin    AdminConfig   `yaml:"admin"`
	Mode     string        `yaml:"mode"`
	Backends BackendConfig `yaml:"backends"`
	Routing  RoutingConfig `yaml:"routing"`
	Limits   LimitsConfig  `yaml:"limits"`
}

type ServerConfig struct {
	Listen string `yaml:"listen"`
}

type AdminConfig struct {
	Listen string `yaml:"listen"`
}

type BackendConfig struct {
	Clusters []ClusterConfig `yaml:"clusters"`
}

type ClusterConfig struct {
	Name  string     `yaml:"name"`
	Nodes []string   `yaml:"nodes"`
	Pool  PoolConfig `yaml:"pool"`
}

type PoolConfig struct {
	ConnectionsPerNode       int `yaml:"connectionsPerNode"`
	MaxInflightPerConnection int `yaml:"maxInflightPerConnection"`
}

type RoutingConfig struct {
	DefaultCluster                     string            `yaml:"defaultCluster"`
	RouteEpoch                         int64             `yaml:"routeEpoch"`
	ClusterSlotsRefreshIntervalSeconds int               `yaml:"clusterSlotsRefreshIntervalSeconds"`
	Rules                              []RouteRuleConfig `yaml:"rules"`
}

type RouteRuleConfig struct {
	Name           string `yaml:"name"`
	Cluster        string `yaml:"cluster"`
	KeyPrefix      string `yaml:"keyPrefix"`
	HashTag        string `yaml:"hashTag"`
	TrafficPercent int    `yaml:"trafficPercent"`
}

type LimitsConfig struct {
	MaxPipelineDepth int `yaml:"maxPipelineDepth"`
	MaxRequestBytes  int `yaml:"maxRequestBytes"`
	MaxResponseBytes int `yaml:"maxResponseBytes"`
}

func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	applyDefaults(&cfg)
	return &cfg, cfg.Validate()
}

func applyDefaults(cfg *Config) {
	if cfg.Server.Listen == "" {
		cfg.Server.Listen = "0.0.0.0:6379"
	}
	if cfg.Admin.Listen == "" {
		cfg.Admin.Listen = "0.0.0.0:8080"
	}
	if cfg.Mode == "" {
		cfg.Mode = "standalone"
	}
	if cfg.Limits.MaxPipelineDepth == 0 {
		cfg.Limits.MaxPipelineDepth = 1024
	}
	if cfg.Limits.MaxRequestBytes == 0 {
		cfg.Limits.MaxRequestBytes = 10 * 1024 * 1024
	}
	if cfg.Limits.MaxResponseBytes == 0 {
		cfg.Limits.MaxResponseBytes = 100 * 1024 * 1024
	}
}

func (c *Config) Validate() error {
	if c.Server.Listen == "" || c.Admin.Listen == "" {
		return errors.New("server.listen and admin.listen are required")
	}
	if c.Mode != "standalone" && c.Mode != "cluster" {
		return fmt.Errorf("unsupported mode %q", c.Mode)
	}
	if c.Routing.DefaultCluster == "" {
		return errors.New("routing.defaultCluster is required")
	}
	if c.Routing.ClusterSlotsRefreshIntervalSeconds < 0 {
		return errors.New("routing.clusterSlotsRefreshIntervalSeconds must be >= 0")
	}
	if len(c.Backends.Clusters) == 0 {
		return errors.New("at least one backend cluster is required")
	}
	seen := map[string]bool{}
	for _, cluster := range c.Backends.Clusters {
		if cluster.Name == "" {
			return errors.New("cluster name is required")
		}
		if seen[cluster.Name] {
			return fmt.Errorf("duplicate cluster %q", cluster.Name)
		}
		seen[cluster.Name] = true
		if len(cluster.Nodes) == 0 {
			return fmt.Errorf("cluster %q must have at least one node", cluster.Name)
		}
	}
	if !seen[c.Routing.DefaultCluster] {
		return fmt.Errorf("default cluster %q not found", c.Routing.DefaultCluster)
	}
	for _, rule := range c.Routing.Rules {
		if rule.Name == "" {
			return errors.New("routing.rules.name is required")
		}
		if !seen[rule.Cluster] {
			return fmt.Errorf("routing rule %q references unknown cluster %q", rule.Name, rule.Cluster)
		}
		if rule.TrafficPercent < 0 || rule.TrafficPercent > 100 {
			return fmt.Errorf("routing rule %q trafficPercent must be between 0 and 100", rule.Name)
		}
		if rule.KeyPrefix == "" && rule.HashTag == "" {
			return fmt.Errorf("routing rule %q must set keyPrefix or hashTag", rule.Name)
		}
	}
	return nil
}
