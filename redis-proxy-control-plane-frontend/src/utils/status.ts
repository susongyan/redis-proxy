import type { ClusterSwitchPlan, ProxyConfig } from '../api/types';

export function convergenceTone(status?: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'CONVERGED':
      return 'success';
    case 'PARTIAL':
    case 'STALE':
      return 'warning';
    case 'DRIFT':
    case 'UNREACHABLE':
      return 'danger';
    default:
      return 'info';
  }
}

export function planTone(status?: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'COMPLETED':
      return 'success';
    case 'RUNNING':
    case 'PRECHECKED':
    case 'PAUSED':
      return 'warning';
    case 'FAILED':
    case 'ROLLED_BACK':
    case 'CANCELLED':
      return 'danger';
    default:
      return 'info';
  }
}

export function canAdvanceClusterSwitch(plan: ClusterSwitchPlan, convergenceStatus: string): boolean {
  if (convergenceStatus !== 'CONVERGED') {
    return false;
  }
  return ['PRECHECKED', 'RUNNING'].includes(plan.status) && plan.status !== 'COMPLETED';
}

export function maskTokens(config: ProxyConfig): ProxyConfig {
  const copy = JSON.parse(JSON.stringify(config)) as ProxyConfig;
  const namespaces = copy.governance?.namespaces || [];
  namespaces.forEach((namespace) => {
    if (namespace.token) {
      namespace.token = '******';
    }
  });
  const clusters = copy.backends?.clusters || [];
  clusters.forEach((cluster) => {
    if (cluster.auth?.password) {
      cluster.auth.password = '******';
    }
  });
  return copy;
}

export function formatDateTime(value?: string | number): string {
  if (!value) {
    return '-';
  }
  const date = typeof value === 'number' ? new Date(value * 1000) : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value);
  }
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(date);
}

export function compactHash(hash?: string): string {
  if (!hash) {
    return '-';
  }
  return hash.length > 16 ? `${hash.slice(0, 12)}...${hash.slice(-6)}` : hash;
}
