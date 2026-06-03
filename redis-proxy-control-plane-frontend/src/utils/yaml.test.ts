import { describe, expect, it } from 'vitest';
import { toYaml } from './yaml';

describe('toYaml', () => {
  it('serializes nested config objects', () => {
    expect(toYaml({ routing: { defaultCluster: 'redis-a', routeEpoch: 2 } })).toContain('defaultCluster: redis-a');
  });

  it('serializes arrays of objects', () => {
    const yaml = toYaml({ clusters: [{ name: 'redis-a', nodes: ['127.0.0.1:6379'] }] });
    expect(yaml).toContain('- name: redis-a');
    expect(yaml).toContain('- 127.0.0.1:6379');
  });
});
