import axios from 'axios';
import type {
  ClusterSwitchPlan,
  ConfigDiff,
  ConfigVersion,
  HistoryResponse,
  KeyObservation,
  ObservabilitySummary,
  ObservabilityTarget,
  ProxyConfig,
  RouteConvergence,
  RouteStatus,
  TargetStatus
} from './types';

export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 10000
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error.response?.data?.message || error.response?.data || error.message || 'request failed';
    return Promise.reject(new Error(typeof message === 'string' ? message : JSON.stringify(message)));
  }
);

export const api = {
  config: {
    current: () => http.get<ProxyConfig>('/config').then((r) => r.data),
    publish: (config: ProxyConfig, operator: string, reason: string, approvalStatus = 'APPROVED') =>
      http.post<ConfigVersion>('/config/publish', { config, operator, reason, approvalStatus }).then((r) => r.data),
    rollback: (payload: { versionId?: number; routeEpoch?: number; operator: string; reason: string; approvalStatus?: string }) =>
      http.post<ConfigVersion>('/config/rollback', payload).then((r) => r.data),
    versions: () => http.get<ConfigVersion[]>('/config/versions').then((r) => r.data),
    version: (versionId: number) => http.get<ConfigVersion>(`/config/versions/${versionId}`).then((r) => r.data),
    diff: (from: number, to: number) => http.get<ConfigDiff>('/config/diff', { params: { from, to } }).then((r) => r.data)
  },
  routes: {
    status: () => http.get<RouteStatus>('/routes/status').then((r) => r.data),
    convergence: () => http.get<RouteConvergence>('/routes/convergence').then((r) => r.data)
  },
  clusterSwitch: {
    plans: () => http.get<ClusterSwitchPlan[]>('/cluster-switch/plans').then((r) => r.data),
    create: (payload: Record<string, unknown>) => http.post<ClusterSwitchPlan>('/cluster-switch/plans', payload).then((r) => r.data),
    precheck: (planId: number) => http.post<ClusterSwitchPlan>(`/cluster-switch/plans/${planId}/precheck`).then((r) => r.data),
    start: (planId: number) => http.post<ClusterSwitchPlan>(`/cluster-switch/plans/${planId}/start`).then((r) => r.data),
    advance: (planId: number) => http.post<ClusterSwitchPlan>(`/cluster-switch/plans/${planId}/advance`).then((r) => r.data),
    jump: (planId: number, trafficPercent: number, operator: string, reason: string) =>
      http.post<ClusterSwitchPlan>(`/cluster-switch/plans/${planId}/jump`, { trafficPercent, operator, reason }).then((r) => r.data),
    rollback: (planId: number, operator: string, reason: string) =>
      http.post<ClusterSwitchPlan>(`/cluster-switch/plans/${planId}/rollback`, { operator, reason }).then((r) => r.data),
    cancel: (planId: number) => http.post<ClusterSwitchPlan>(`/cluster-switch/plans/${planId}/cancel`).then((r) => r.data)
  },
  observability: {
    summary: () => http.get<ObservabilitySummary>('/observability/summary').then((r) => r.data),
    targets: () => http.get<TargetStatus[]>('/observability/targets').then((r) => r.data),
    registerTarget: (target: ObservabilityTarget) => http.post<TargetStatus>('/observability/targets', target).then((r) => r.data),
    deleteTarget: (proxyId: string) => http.delete(`/observability/targets/${proxyId}`),
    hotKeys: (params?: Record<string, unknown>) => http.get<KeyObservation[]>('/observability/hot-keys', { params }).then((r) => r.data),
    largeKeys: (params?: Record<string, unknown>) => http.get<KeyObservation[]>('/observability/large-keys', { params }).then((r) => r.data),
    slowQueries: (params?: Record<string, unknown>) => http.get<KeyObservation[]>('/observability/slow-queries', { params }).then((r) => r.data),
    history: (params?: Record<string, unknown>) => http.get<HistoryResponse>('/observability/history', { params }).then((r) => r.data),
    prometheusUrl: '/api/v1/observability/prometheus'
  }
};
