package config

import (
	"errors"
	"fmt"
	"os"
	"runtime"
	"strings"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Instance     InstanceConfig     `yaml:"instance" json:"instance"`
	Server       ServerConfig       `yaml:"server" json:"server"`
	Admin        AdminConfig        `yaml:"admin" json:"admin"`
	Mode         string             `yaml:"mode" json:"mode"`
	Backends     BackendConfig      `yaml:"backends" json:"backends"`
	Routing      RoutingConfig      `yaml:"routing" json:"routing"`
	Limits       LimitsConfig       `yaml:"limits" json:"limits"`
	ControlPlane ControlPlaneConfig `yaml:"controlPlane" json:"controlPlane"`
	Analysis     AnalysisConfig     `yaml:"analysis" json:"analysis"`
	Governance   GovernanceConfig   `yaml:"governance" json:"governance"`
}

type InstanceConfig struct {
	ProxyID string `yaml:"proxyId" json:"proxyId"`
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
	BackendAffinityStrategy            string            `yaml:"backendAffinityStrategy" json:"backendAffinityStrategy"`
	Rules                              []RouteRuleConfig `yaml:"rules" json:"rules"`
}

type RouteRuleConfig struct {
	Name           string `yaml:"name" json:"name"`
	Cluster        string `yaml:"cluster" json:"cluster"`
	Namespace      string `yaml:"namespace" json:"namespace"`
	KeyPrefix      string `yaml:"keyPrefix" json:"keyPrefix"`
	KeyPattern     string `yaml:"keyPattern" json:"keyPattern"`
	HashTag        string `yaml:"hashTag" json:"hashTag"`
	TrafficPercent int    `yaml:"trafficPercent" json:"trafficPercent"`
}

type LimitsConfig struct {
	MaxPipelineDepth            int `yaml:"maxPipelineDepth" json:"maxPipelineDepth"`
	PipelineFlushBatchSize      int `yaml:"pipelineFlushBatchSize" json:"pipelineFlushBatchSize"`
	PipelineFlushMaxDelayMillis int `yaml:"pipelineFlushMaxDelayMillis" json:"pipelineFlushMaxDelayMillis"`
	MaxRequestBytes             int `yaml:"maxRequestBytes" json:"maxRequestBytes"`
	MaxResponseBytes            int `yaml:"maxResponseBytes" json:"maxResponseBytes"`
	LargeResponseBytes          int `yaml:"largeResponseBytes" json:"largeResponseBytes"`
}

type AnalysisConfig struct {
	HotKey    HotKeyAnalysisConfig    `yaml:"hotKey" json:"hotKey"`
	LargeKey  LargeKeyAnalysisConfig  `yaml:"largeKey" json:"largeKey"`
	SlowQuery SlowQueryAnalysisConfig `yaml:"slowQuery" json:"slowQuery"`
}

type HotKeyAnalysisConfig struct {
	Enabled        *bool `yaml:"enabled" json:"enabled"`
	WindowSeconds  int   `yaml:"windowSeconds" json:"windowSeconds"`
	BucketMillis   int   `yaml:"bucketMillis" json:"bucketMillis"`
	MaxTrackedKeys int   `yaml:"maxTrackedKeys" json:"maxTrackedKeys"`
	MetricsTopN    int   `yaml:"metricsTopN" json:"metricsTopN"`
}

type LargeKeyAnalysisConfig struct {
	Enabled                *bool `yaml:"enabled" json:"enabled"`
	RequestBytesThreshold  int   `yaml:"requestBytesThreshold" json:"requestBytesThreshold"`
	ResponseBytesThreshold int   `yaml:"responseBytesThreshold" json:"responseBytesThreshold"`
	WindowSeconds          int   `yaml:"windowSeconds" json:"windowSeconds"`
	BucketMillis           int   `yaml:"bucketMillis" json:"bucketMillis"`
	MaxTrackedKeys         int   `yaml:"maxTrackedKeys" json:"maxTrackedKeys"`
	DebugTopN              int   `yaml:"debugTopN" json:"debugTopN"`
}

type SlowQueryAnalysisConfig struct {
	Enabled                 *bool `yaml:"enabled" json:"enabled"`
	EndToEndThresholdMillis int   `yaml:"endToEndThresholdMillis" json:"endToEndThresholdMillis"`
	BackendThresholdMillis  int   `yaml:"backendThresholdMillis" json:"backendThresholdMillis"`
	WindowSeconds           int   `yaml:"windowSeconds" json:"windowSeconds"`
	BucketMillis            int   `yaml:"bucketMillis" json:"bucketMillis"`
	MaxTrackedKeys          int   `yaml:"maxTrackedKeys" json:"maxTrackedKeys"`
	DebugTopN               int   `yaml:"debugTopN" json:"debugTopN"`
}

type ControlPlaneConfig struct {
	Enabled              bool   `yaml:"enabled" json:"enabled"`
	URL                  string `yaml:"url" json:"url"`
	PollIntervalSeconds  int    `yaml:"pollIntervalSeconds" json:"pollIntervalSeconds"`
	WatchTimeoutSeconds  int    `yaml:"watchTimeoutSeconds" json:"watchTimeoutSeconds"`
	RequestTimeoutMillis int    `yaml:"requestTimeoutMillis" json:"requestTimeoutMillis"`
}

type GovernanceConfig struct {
	Enabled              bool                `yaml:"enabled" json:"enabled"`
	RequireAuth          bool                `yaml:"requireAuth" json:"requireAuth"`
	KeyLimitWindowMillis int                 `yaml:"keyLimitWindowMillis" json:"keyLimitWindowMillis"`
	KeyLimitBucketMillis int                 `yaml:"keyLimitBucketMillis" json:"keyLimitBucketMillis"`
	CommandPolicy        CommandPolicyConfig `yaml:"commandPolicy" json:"commandPolicy"`
	Namespaces           []NamespaceConfig   `yaml:"namespaces" json:"namespaces"`
	namespacesByName     map[string]NamespaceConfig
}

type CommandPolicyConfig struct {
	DeniedCommands   []string `yaml:"deniedCommands" json:"deniedCommands"`
	WarnOnlyCommands []string `yaml:"warnOnlyCommands" json:"warnOnlyCommands"`
}

type NamespaceConfig struct {
	Name               string                `yaml:"name" json:"name"`
	Token              string                `yaml:"token" json:"token"`
	ReadOnly           bool                  `yaml:"readOnly" json:"readOnly"`
	AllowedKeyPrefixes []string              `yaml:"allowedKeyPrefixes" json:"allowedKeyPrefixes"`
	DeniedCommands     []string              `yaml:"deniedCommands" json:"deniedCommands"`
	WarnOnlyCommands   []string              `yaml:"warnOnlyCommands" json:"warnOnlyCommands"`
	Limits             NamespaceLimitsConfig `yaml:"limits" json:"limits"`
	DisabledKeys       []string              `yaml:"disabledKeys" json:"disabledKeys"`
	KeyRules           []KeyRuleConfig       `yaml:"keyRules" json:"keyRules"`
}

type NamespaceLimitsConfig struct {
	MaxConnections int `yaml:"maxConnections" json:"maxConnections"`
	MaxQPS         int `yaml:"maxQps" json:"maxQps"`
	MaxInflight    int `yaml:"maxInflight" json:"maxInflight"`
}

type KeyRuleConfig struct {
	Name      string `yaml:"name" json:"name"`
	KeyPrefix string `yaml:"keyPrefix" json:"keyPrefix"`
	HashTag   string `yaml:"hashTag" json:"hashTag"`
	Disabled  bool   `yaml:"disabled" json:"disabled"`
	MaxQPS    int    `yaml:"maxQps" json:"maxQps"`
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
	if cfg.Instance.ProxyID == "" {
		if host, err := os.Hostname(); err == nil && host != "" {
			cfg.Instance.ProxyID = host
		} else {
			cfg.Instance.ProxyID = "proxy-" + runtime.GOOS
		}
	}
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
	if cfg.Limits.PipelineFlushBatchSize == 0 {
		cfg.Limits.PipelineFlushBatchSize = 16
	}
	if cfg.Limits.PipelineFlushMaxDelayMillis == 0 {
		cfg.Limits.PipelineFlushMaxDelayMillis = 1
	}
	if cfg.Limits.MaxRequestBytes == 0 {
		cfg.Limits.MaxRequestBytes = 10 * 1024 * 1024
	}
	if cfg.Limits.MaxResponseBytes == 0 {
		cfg.Limits.MaxResponseBytes = 100 * 1024 * 1024
	}
	if cfg.Limits.LargeResponseBytes == 0 {
		cfg.Limits.LargeResponseBytes = 1024 * 1024
	}
	if cfg.Routing.BackendAffinityStrategy == "" {
		cfg.Routing.BackendAffinityStrategy = "client"
	}
	applyAnalysisDefaults(&cfg.Analysis)
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
	if cfg.Governance.KeyLimitWindowMillis == 0 {
		cfg.Governance.KeyLimitWindowMillis = 1000
	}
	if cfg.Governance.KeyLimitBucketMillis == 0 {
		cfg.Governance.KeyLimitBucketMillis = 100
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
	cfg.Governance.BuildIndex()
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
	if c.Limits.MaxPipelineDepth <= 0 || c.Limits.PipelineFlushBatchSize <= 0 || c.Limits.PipelineFlushMaxDelayMillis < 0 || c.Limits.MaxRequestBytes <= 0 || c.Limits.MaxResponseBytes <= 0 || c.Limits.LargeResponseBytes < 0 {
		return errors.New("limits must be positive, pipelineFlushMaxDelayMillis must be >= 0 and largeResponseBytes must be >= 0")
	}
	if !validBackendAffinityStrategy(c.Routing.BackendAffinityStrategy) {
		return fmt.Errorf("routing.backendAffinityStrategy must be client, keySlot or hashTag")
	}
	if err := c.validateAnalysis(); err != nil {
		return err
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
	namespaces := map[string]bool{}
	for _, namespace := range c.Governance.Namespaces {
		namespaces[namespace.Name] = true
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
		if rule.Namespace != "" && !namespaces[rule.Namespace] {
			return fmt.Errorf("routing rule %q references unknown namespace %q", rule.Name, rule.Namespace)
		}
		if rule.Namespace == "" && rule.KeyPrefix == "" && rule.KeyPattern == "" && rule.HashTag == "" {
			return fmt.Errorf("routing rule %q must set namespace, keyPrefix, keyPattern or hashTag", rule.Name)
		}
	}
	if err := c.validateGovernance(); err != nil {
		return err
	}
	return nil
}

func validBackendAffinityStrategy(strategy string) bool {
	return strategy == "client" || strategy == "keySlot" || strategy == "hashTag"
}

func applyAnalysisDefaults(analysis *AnalysisConfig) {
	if analysis.HotKey.Enabled == nil {
		enabled := true
		analysis.HotKey.Enabled = &enabled
	}
	if analysis.HotKey.WindowSeconds == 0 {
		analysis.HotKey.WindowSeconds = 60
	}
	if analysis.HotKey.BucketMillis == 0 {
		analysis.HotKey.BucketMillis = 1000
	}
	if analysis.HotKey.MaxTrackedKeys == 0 {
		analysis.HotKey.MaxTrackedKeys = 10000
	}
	if analysis.HotKey.MetricsTopN == 0 {
		analysis.HotKey.MetricsTopN = 20
	}
	if analysis.LargeKey.Enabled == nil {
		enabled := true
		analysis.LargeKey.Enabled = &enabled
	}
	if analysis.LargeKey.RequestBytesThreshold == 0 {
		analysis.LargeKey.RequestBytesThreshold = 1024 * 1024
	}
	if analysis.LargeKey.ResponseBytesThreshold == 0 {
		analysis.LargeKey.ResponseBytesThreshold = 1024 * 1024
	}
	if analysis.LargeKey.WindowSeconds == 0 {
		analysis.LargeKey.WindowSeconds = 300
	}
	if analysis.LargeKey.BucketMillis == 0 {
		analysis.LargeKey.BucketMillis = 1000
	}
	if analysis.LargeKey.MaxTrackedKeys == 0 {
		analysis.LargeKey.MaxTrackedKeys = 10000
	}
	if analysis.LargeKey.DebugTopN == 0 {
		analysis.LargeKey.DebugTopN = 100
	}
	if analysis.SlowQuery.Enabled == nil {
		enabled := true
		analysis.SlowQuery.Enabled = &enabled
	}
	if analysis.SlowQuery.EndToEndThresholdMillis == 0 {
		analysis.SlowQuery.EndToEndThresholdMillis = 100
	}
	if analysis.SlowQuery.BackendThresholdMillis == 0 {
		analysis.SlowQuery.BackendThresholdMillis = 50
	}
	if analysis.SlowQuery.WindowSeconds == 0 {
		analysis.SlowQuery.WindowSeconds = 300
	}
	if analysis.SlowQuery.BucketMillis == 0 {
		analysis.SlowQuery.BucketMillis = 1000
	}
	if analysis.SlowQuery.MaxTrackedKeys == 0 {
		analysis.SlowQuery.MaxTrackedKeys = 10000
	}
	if analysis.SlowQuery.DebugTopN == 0 {
		analysis.SlowQuery.DebugTopN = 100
	}
}

func (h HotKeyAnalysisConfig) IsEnabled() bool {
	return h.Enabled == nil || *h.Enabled
}

func (l LargeKeyAnalysisConfig) IsEnabled() bool {
	return l.Enabled == nil || *l.Enabled
}

func (s SlowQueryAnalysisConfig) IsEnabled() bool {
	return s.Enabled == nil || *s.Enabled
}

func (c *Config) validateAnalysis() error {
	windowMillis := c.Analysis.HotKey.WindowSeconds * 1000
	if c.Analysis.HotKey.WindowSeconds <= 0 ||
		c.Analysis.HotKey.BucketMillis <= 0 ||
		c.Analysis.HotKey.MaxTrackedKeys <= 0 ||
		c.Analysis.HotKey.MetricsTopN <= 0 {
		return errors.New("analysis.hotKey windowSeconds, bucketMillis, maxTrackedKeys and metricsTopN must be > 0")
	}
	if windowMillis%c.Analysis.HotKey.BucketMillis != 0 {
		return errors.New("analysis.hotKey windowSeconds must be divisible by bucketMillis")
	}
	largeWindowMillis := c.Analysis.LargeKey.WindowSeconds * 1000
	if c.Analysis.LargeKey.RequestBytesThreshold < 0 ||
		c.Analysis.LargeKey.ResponseBytesThreshold < 0 ||
		c.Analysis.LargeKey.WindowSeconds <= 0 ||
		c.Analysis.LargeKey.BucketMillis <= 0 ||
		c.Analysis.LargeKey.MaxTrackedKeys <= 0 ||
		c.Analysis.LargeKey.DebugTopN <= 0 {
		return errors.New("analysis.largeKey thresholds must be >= 0 and windowSeconds, bucketMillis, maxTrackedKeys and debugTopN must be > 0")
	}
	if largeWindowMillis%c.Analysis.LargeKey.BucketMillis != 0 {
		return errors.New("analysis.largeKey windowSeconds must be divisible by bucketMillis")
	}
	slowWindowMillis := c.Analysis.SlowQuery.WindowSeconds * 1000
	if c.Analysis.SlowQuery.EndToEndThresholdMillis < 0 ||
		c.Analysis.SlowQuery.BackendThresholdMillis < 0 ||
		c.Analysis.SlowQuery.WindowSeconds <= 0 ||
		c.Analysis.SlowQuery.BucketMillis <= 0 ||
		c.Analysis.SlowQuery.MaxTrackedKeys <= 0 ||
		c.Analysis.SlowQuery.DebugTopN <= 0 {
		return errors.New("analysis.slowQuery thresholds must be >= 0 and windowSeconds, bucketMillis, maxTrackedKeys and debugTopN must be > 0")
	}
	if slowWindowMillis%c.Analysis.SlowQuery.BucketMillis != 0 {
		return errors.New("analysis.slowQuery windowSeconds must be divisible by bucketMillis")
	}
	return nil
}

func (c *Config) validateGovernance() error {
	if c.Governance.KeyLimitWindowMillis == 0 {
		c.Governance.KeyLimitWindowMillis = 1000
	}
	if c.Governance.KeyLimitBucketMillis == 0 {
		c.Governance.KeyLimitBucketMillis = 100
	}
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
		if namespace.Limits.MaxConnections < 0 || namespace.Limits.MaxQPS < 0 || namespace.Limits.MaxInflight < 0 {
			return fmt.Errorf("governance namespace %q limits must be >= 0", namespace.Name)
		}
		for _, command := range append(append([]string{}, namespace.DeniedCommands...), namespace.WarnOnlyCommands...) {
			if !validCommand(command) {
				return fmt.Errorf("governance namespace %q has invalid command %q", namespace.Name, command)
			}
		}
		ruleNames := map[string]bool{}
		for _, rule := range namespace.KeyRules {
			if rule.Name == "" {
				return fmt.Errorf("governance namespace %q keyRules.name is required", namespace.Name)
			}
			if ruleNames[rule.Name] {
				return fmt.Errorf("governance namespace %q has duplicate key rule %q", namespace.Name, rule.Name)
			}
			ruleNames[rule.Name] = true
			if rule.KeyPrefix == "" && rule.HashTag == "" {
				return fmt.Errorf("governance namespace %q key rule %q must set keyPrefix or hashTag", namespace.Name, rule.Name)
			}
			if rule.MaxQPS < 0 {
				return fmt.Errorf("governance namespace %q key rule %q maxQps must be >= 0", namespace.Name, rule.Name)
			}
		}
	}
	if c.Governance.KeyLimitWindowMillis <= 0 || c.Governance.KeyLimitBucketMillis <= 0 {
		return errors.New("governance key limit window and bucket must be > 0")
	}
	if c.Governance.KeyLimitWindowMillis%c.Governance.KeyLimitBucketMillis != 0 {
		return errors.New("governance keyLimitWindowMillis must be divisible by keyLimitBucketMillis")
	}
	for _, command := range append(append([]string{}, c.Governance.CommandPolicy.DeniedCommands...), c.Governance.CommandPolicy.WarnOnlyCommands...) {
		if !validCommand(command) {
			return fmt.Errorf("governance commandPolicy has invalid command %q", command)
		}
	}
	c.Governance.BuildIndex()
	return nil
}

func (g *GovernanceConfig) BuildIndex() {
	index := make(map[string]NamespaceConfig, len(g.Namespaces))
	for _, namespace := range g.Namespaces {
		if namespace.Name != "" {
			index[namespace.Name] = namespace
		}
	}
	g.namespacesByName = index
}

func (g GovernanceConfig) NamespaceByName(name string) (NamespaceConfig, bool) {
	if g.namespacesByName != nil {
		namespace, ok := g.namespacesByName[name]
		return namespace, ok
	}
	for _, namespace := range g.Namespaces {
		if namespace.Name == name {
			return namespace, true
		}
	}
	return NamespaceConfig{}, false
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
