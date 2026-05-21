package config

import (
	"errors"
	"fmt"
	"os"
	"strings"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Server       ServerConfig       `yaml:"server" json:"server"`
	Admin        AdminConfig        `yaml:"admin" json:"admin"`
	Mode         string             `yaml:"mode" json:"mode"`
	Backends     BackendConfig      `yaml:"backends" json:"backends"`
	Routing      RoutingConfig      `yaml:"routing" json:"routing"`
	Limits       LimitsConfig       `yaml:"limits" json:"limits"`
	ControlPlane ControlPlaneConfig `yaml:"controlPlane" json:"controlPlane"`
	Governance   GovernanceConfig   `yaml:"governance" json:"governance"`
}

type ServerConfig struct {
	Listen string `yaml:"listen" json:"listen"`
}

type AdminConfig struct {
	Listen string `yaml:"listen" json:"listen"`
}

type BackendConfig struct {
	Clusters []ClusterConfig `yaml:"clusters" json:"clusters"`
}

type ClusterConfig struct {
	Name  string     `yaml:"name" json:"name"`
	Nodes []string   `yaml:"nodes" json:"nodes"`
	Pool  PoolConfig `yaml:"pool" json:"pool"`
}

type PoolConfig struct {
	ConnectionsPerNode       int `yaml:"connectionsPerNode" json:"connectionsPerNode"`
	MaxInflightPerConnection int `yaml:"maxInflightPerConnection" json:"maxInflightPerConnection"`
}

type RoutingConfig struct {
	DefaultCluster                     string            `yaml:"defaultCluster" json:"defaultCluster"`
	RouteEpoch                         int64             `yaml:"routeEpoch" json:"routeEpoch"`
	ClusterSlotsRefreshIntervalSeconds int               `yaml:"clusterSlotsRefreshIntervalSeconds" json:"clusterSlotsRefreshIntervalSeconds"`
	Rules                              []RouteRuleConfig `yaml:"rules" json:"rules"`
}

type RouteRuleConfig struct {
	Name           string `yaml:"name" json:"name"`
	Cluster        string `yaml:"cluster" json:"cluster"`
	KeyPrefix      string `yaml:"keyPrefix" json:"keyPrefix"`
	HashTag        string `yaml:"hashTag" json:"hashTag"`
	TrafficPercent int    `yaml:"trafficPercent" json:"trafficPercent"`
}

type LimitsConfig struct {
	MaxPipelineDepth int `yaml:"maxPipelineDepth" json:"maxPipelineDepth"`
	MaxRequestBytes  int `yaml:"maxRequestBytes" json:"maxRequestBytes"`
	MaxResponseBytes int `yaml:"maxResponseBytes" json:"maxResponseBytes"`
}

type ControlPlaneConfig struct {
	Enabled              bool   `yaml:"enabled" json:"enabled"`
	URL                  string `yaml:"url" json:"url"`
	PollIntervalSeconds  int    `yaml:"pollIntervalSeconds" json:"pollIntervalSeconds"`
	WatchTimeoutSeconds  int    `yaml:"watchTimeoutSeconds" json:"watchTimeoutSeconds"`
	RequestTimeoutMillis int    `yaml:"requestTimeoutMillis" json:"requestTimeoutMillis"`
}

type GovernanceConfig struct {
	Enabled       bool                `yaml:"enabled" json:"enabled"`
	RequireAuth   bool                `yaml:"requireAuth" json:"requireAuth"`
	CommandPolicy CommandPolicyConfig `yaml:"commandPolicy" json:"commandPolicy"`
	Namespaces    []NamespaceConfig   `yaml:"namespaces" json:"namespaces"`
}

type CommandPolicyConfig struct {
	DeniedCommands   []string `yaml:"deniedCommands" json:"deniedCommands"`
	WarnOnlyCommands []string `yaml:"warnOnlyCommands" json:"warnOnlyCommands"`
}

type NamespaceConfig struct {
	Name               string   `yaml:"name" json:"name"`
	Token              string   `yaml:"token" json:"token"`
	ReadOnly           bool     `yaml:"readOnly" json:"readOnly"`
	AllowedKeyPrefixes []string `yaml:"allowedKeyPrefixes" json:"allowedKeyPrefixes"`
	DeniedCommands     []string `yaml:"deniedCommands" json:"deniedCommands"`
	WarnOnlyCommands   []string `yaml:"warnOnlyCommands" json:"warnOnlyCommands"`
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
	ApplyDefaults(&cfg)
	return &cfg, cfg.Validate()
}

func ApplyDefaults(cfg *Config) {
	applyDefaults(cfg)
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
	if cfg.ControlPlane.PollIntervalSeconds == 0 {
		cfg.ControlPlane.PollIntervalSeconds = 5
	}
	if cfg.ControlPlane.WatchTimeoutSeconds == 0 {
		cfg.ControlPlane.WatchTimeoutSeconds = 30
	}
	if cfg.ControlPlane.RequestTimeoutMillis == 0 {
		cfg.ControlPlane.RequestTimeoutMillis = 1000
	}
	if cfg.Governance.Enabled && !cfg.Governance.RequireAuth {
		cfg.Governance.RequireAuth = true
	}
	if len(cfg.Governance.CommandPolicy.DeniedCommands) == 0 {
		cfg.Governance.CommandPolicy.DeniedCommands = []string{"FLUSHALL", "FLUSHDB", "CONFIG", "SHUTDOWN", "DEBUG", "MODULE"}
	}
	if len(cfg.Governance.CommandPolicy.WarnOnlyCommands) == 0 {
		cfg.Governance.CommandPolicy.WarnOnlyCommands = []string{"KEYS", "EVAL", "SCRIPT"}
	}
	normalizeCommands(&cfg.Governance.CommandPolicy.DeniedCommands)
	normalizeCommands(&cfg.Governance.CommandPolicy.WarnOnlyCommands)
	for i := range cfg.Governance.Namespaces {
		normalizeCommands(&cfg.Governance.Namespaces[i].DeniedCommands)
		normalizeCommands(&cfg.Governance.Namespaces[i].WarnOnlyCommands)
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
	if c.Routing.RouteEpoch < 0 {
		return errors.New("routing.routeEpoch must be >= 0")
	}
	if c.ControlPlane.Enabled && c.ControlPlane.URL == "" {
		return errors.New("controlPlane.url is required when controlPlane.enabled=true")
	}
	if c.ControlPlane.PollIntervalSeconds < 0 {
		return errors.New("controlPlane.pollIntervalSeconds must be >= 0")
	}
	if c.ControlPlane.WatchTimeoutSeconds < 0 {
		return errors.New("controlPlane.watchTimeoutSeconds must be >= 0")
	}
	if c.ControlPlane.RequestTimeoutMillis < 0 {
		return errors.New("controlPlane.requestTimeoutMillis must be >= 0")
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
	if err := c.validateGovernance(); err != nil {
		return err
	}
	return nil
}

func (c *Config) validateGovernance() error {
	seen := map[string]bool{}
	for _, namespace := range c.Governance.Namespaces {
		if namespace.Name == "" {
			return errors.New("governance.namespaces.name is required")
		}
		if namespace.Token == "" {
			return fmt.Errorf("governance namespace %q token is required", namespace.Name)
		}
		if seen[namespace.Name] {
			return fmt.Errorf("duplicate governance namespace %q", namespace.Name)
		}
		seen[namespace.Name] = true
		for _, command := range append(append([]string{}, namespace.DeniedCommands...), namespace.WarnOnlyCommands...) {
			if !validCommand(command) {
				return fmt.Errorf("governance namespace %q has invalid command %q", namespace.Name, command)
			}
		}
	}
	for _, command := range append(append([]string{}, c.Governance.CommandPolicy.DeniedCommands...), c.Governance.CommandPolicy.WarnOnlyCommands...) {
		if !validCommand(command) {
			return fmt.Errorf("governance commandPolicy has invalid command %q", command)
		}
	}
	return nil
}

func normalizeCommands(commands *[]string) {
	for i, command := range *commands {
		(*commands)[i] = strings.ToUpper(strings.TrimSpace(command))
	}
}

func validCommand(command string) bool {
	if command == "" {
		return false
	}
	for _, r := range command {
		if (r < 'A' || r > 'Z') && (r < '0' || r > '9') && r != '_' {
			return false
		}
	}
	return true
}
