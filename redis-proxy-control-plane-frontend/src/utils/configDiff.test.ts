import { describe, expect, it } from 'vitest';
import { buildSideBySideYamlDiff, buildYamlDiff } from './configDiff';

describe('buildYamlDiff', () => {
  it('highlights added and removed yaml lines', () => {
    const diff = buildYamlDiff(
      {
        routing: { defaultCluster: 'redis-a', routeEpoch: 1 },
        limits: { maxPipelineDepth: 1024 }
      },
      {
        routing: { defaultCluster: 'redis-b', routeEpoch: 2 },
        limits: { maxPipelineDepth: 2048 }
      }
    );

    expect(diff.stats.added).toBeGreaterThan(0);
    expect(diff.stats.removed).toBeGreaterThan(0);
    expect(diff.lines.some((line) => line.kind === 'remove' && line.text.includes('redis-a'))).toBe(true);
    expect(diff.lines.some((line) => line.kind === 'add' && line.text.includes('redis-b'))).toBe(true);
  });

  it('masks sensitive token and redis password values', () => {
    const diff = buildYamlDiff(
      {
        backends: { clusters: [{ name: 'redis-a', nodes: ['127.0.0.1:6379'], auth: { enabled: true, password: 'old-secret' } }] },
        governance: { namespaces: [{ name: 'app-a', token: 'old-token' }] }
      },
      {
        backends: { clusters: [{ name: 'redis-a', nodes: ['127.0.0.1:6379'], auth: { enabled: true, password: 'new-secret' } }] },
        governance: { namespaces: [{ name: 'app-a', token: 'new-token' }] }
      }
    );

    const text = diff.lines.map((line) => line.text).join('\n');
    expect(text).not.toContain('old-secret');
    expect(text).not.toContain('new-secret');
    expect(text).not.toContain('old-token');
    expect(text).not.toContain('new-token');
    expect(text).toContain('******');
  });

  it('builds side-by-side rows for full config comparison', () => {
    const rows = buildSideBySideYamlDiff(
      {
        routing: { defaultCluster: 'redis-a', routeEpoch: 1 },
        limits: { maxPipelineDepth: 1024 }
      },
      {
        routing: { defaultCluster: 'redis-b', routeEpoch: 2 },
        limits: { maxPipelineDepth: 1024 }
      }
    );

    expect(rows.some((row) => row.kind === 'same' && row.left.text === row.right.text)).toBe(true);
    expect(rows.some((row) => row.kind === 'change' && row.left.text?.includes('redis-a') && row.right.text?.includes('redis-b'))).toBe(true);
  });
});
