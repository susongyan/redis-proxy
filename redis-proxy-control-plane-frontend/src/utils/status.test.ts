import { describe, expect, it } from 'vitest';
import { canAdvanceClusterSwitch, convergenceTone, maskTokens } from './status';
import type { ClusterSwitchPlan, ProxyConfig } from '../api/types';

describe('status helpers', () => {
  it('maps convergence status to UI tone', () => {
    expect(convergenceTone('CONVERGED')).toBe('success');
    expect(convergenceTone('STALE')).toBe('warning');
    expect(convergenceTone('DRIFT')).toBe('danger');
  });

  it('blocks cluster switch advance before route convergence', () => {
    const plan = { status: 'RUNNING' } as ClusterSwitchPlan;
    expect(canAdvanceClusterSwitch(plan, 'CONVERGED')).toBe(true);
    expect(canAdvanceClusterSwitch(plan, 'STALE')).toBe(false);
  });

  it('masks namespace tokens without mutating source config', () => {
    const config: ProxyConfig = {
      backends: {
        clusters: [{ name: 'redis-a', nodes: [], auth: { enabled: true, username: '', password: 'redis-secret' } }]
      },
      governance: {
        namespaces: [{ name: 'app-a', token: 'secret-token' }]
      }
    };
    const masked = maskTokens(config);
    expect(masked.governance?.namespaces?.[0].token).toBe('******');
    expect(masked.backends?.clusters?.[0].auth?.password).toBe('******');
    expect(config.governance?.namespaces?.[0].token).toBe('secret-token');
    expect(config.backends?.clusters?.[0].auth?.password).toBe('redis-secret');
  });
});
