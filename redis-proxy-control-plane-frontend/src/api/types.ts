export interface ProxyConfig {
  server?: { listen?: string };
  admin?: { listen?: string };
  mode?: string;
  backends?: { clusters?: Cluster[] };
  routing?: Routing;
  limits?: Limits;
  analysis?: Analysis;
  governance?: Governance;
  [key: string]: unknown;
}

export interface Cluster {
  name: string;
  nodes: string[];
  auth?: {
    enabled?: boolean;
    username?: string;
    password?: string;
  };
  pool?: {
    connectionsPerNode?: number;
    maxInflightPerConnection?: number;
  };
}

export interface Routing {
  defaultCluster?: string;
  routeEpoch?: number;
  clusterSlotsRefreshIntervalSeconds?: number;
  backendAffinityStrategy?: string;
  rules?: RouteRule[];
}

export interface RouteRule {
  name: string;
  cluster: string;
  namespace?: string;
  keyPrefix?: string;
  keyPattern?: string;
  hashTag?: string;
  matchAll?: boolean;
  trafficPercent?: number;
}

export interface Limits {
  maxPipelineDepth?: number;
  pipelineFlushBatchSize?: number;
  pipelineFlushMaxDelayMillis?: number;
  maxRequestBytes?: number;
  maxResponseBytes?: number;
  largeResponseBytes?: number;
}

export interface Analysis {
  hotKey?: AnalysisWindow;
  largeKey?: AnalysisWindow & {
    requestBytesThreshold?: number;
    responseBytesThreshold?: number;
    debugTopN?: number;
  };
  slowQuery?: AnalysisWindow & {
    endToEndThresholdMillis?: number;
    backendThresholdMillis?: number;
    debugTopN?: number;
  };
}

export interface AnalysisWindow {
  enabled?: boolean;
  windowSeconds?: number;
  bucketMillis?: number;
  maxTrackedKeys?: number;
  metricsTopN?: number;
}

export interface Governance {
  enabled?: boolean;
  requireAuth?: boolean;
  keyLimitWindowMillis?: number;
  keyLimitBucketMillis?: number;
  commandPolicy?: {
    deniedCommands?: string[];
    warnOnlyCommands?: string[];
  };
  namespaces?: Namespace[];
}

export interface Namespace {
  name: string;
  token?: string;
  readOnly?: boolean;
  allowedKeyPrefixes?: string[];
  deniedCommands?: string[];
  warnOnlyCommands?: string[];
  limits?: {
    maxConnections?: number;
    maxQps?: number;
    maxInflight?: number;
  };
  disabledKeys?: string[];
  keyRules?: KeyRule[];
}

export interface KeyRule {
  name: string;
  keyPrefix?: string;
  hashTag?: string;
  disabled?: boolean;
  maxQps?: number;
}

export interface ConfigVersion {
  versionId: number;
  publishedAt: string;
  operator: string;
  reason: string;
  action: string;
  approvalStatus: string;
  rollbackFromVersionId?: number;
  routeEpoch: number;
  config: ProxyConfig;
}

export interface ConfigDiff {
  fromVersionId: number;
  toVersionId: number;
  changes: string[];
}

export interface RouteStatus {
  currentVersionId: number;
  routeEpoch: number;
  expectedVersionId: number;
  expectedRouteEpoch: number;
  expectedConfigHash: string;
  defaultCluster: string;
  rules: RouteRule[];
  clusters: string[];
  lastPublished?: ConfigVersion;
}

export interface RouteConvergence {
  expectedVersionId: number;
  expectedRouteEpoch: number;
  expectedConfigHash: string;
  status: string;
  total: number;
  converged: number;
  stale: number;
  drift: number;
  unreachable: number;
  proxies: RouteConvergenceInstance[];
}

export interface RouteConvergenceInstance {
  proxyId: string;
  dataplane: string;
  adminUrl: string;
  healthy: boolean;
  epoch: number;
  configHash: string;
  lastApplyResult: string;
  lastApplyTime: number;
  lastPollTime: number;
  collectedAt?: string;
  status: string;
  reason: string;
}

export interface ClusterSwitchPlan {
  planId: number;
  sourceCluster: string;
  targetCluster: string;
  mode: 'STAGED' | 'FULL' | string;
  status: string;
  steps: number[];
  currentStepIndex: number;
  operator: string;
  reason: string;
  baselineVersionId: number;
  targetClusterDefinition?: Cluster;
  publishedSteps: PublishedStep[];
  createdAt: string;
  updatedAt: string;
}

export interface PublishedStep {
  trafficPercent: number;
  versionId: number;
  routeEpoch: number;
  action: string;
  publishedAt: string;
}

export interface ObservabilityTarget {
  proxyId: string;
  adminUrl: string;
  dataplane: string;
  cluster?: string;
  pollIntervalSeconds?: number;
  resourceAttributes?: Record<string, string>;
}

export interface TargetStatus extends ObservabilityTarget {
  healthy: boolean;
  lastCollectedAt?: string;
  lastError?: string;
}

export interface ObservabilitySummary {
  targets: TargetStatus[];
  totals: Record<string, number>;
}

export interface KeyObservation {
  proxyId: string;
  dataplane: string;
  cluster: string;
  namespace: string;
  command: string;
  key: string;
  count: number;
  maxRequestBytes?: number;
  maxResponseBytes?: number;
  maxEndToEndMillis?: number;
  maxBackendMillis?: number;
  collectedAt: string;
  proxyIds?: string[];
}

export interface HistoryPoint {
  timestamp: string;
  metric: string;
  value: number;
  labels: Record<string, string>;
  resourceAttributes: Record<string, string>;
}

export interface HistoryResponse {
  metric?: string;
  from?: string;
  to?: string;
  stepSeconds: number;
  points: HistoryPoint[];
}
