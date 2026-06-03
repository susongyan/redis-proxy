<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import { computed, onMounted } from 'vue';
import MetricStrip from '../components/MetricStrip.vue';
import { useControlPlaneStore } from '../stores/controlPlane';
import { maskTokens } from '../utils/status';

const store = useControlPlaneStore();

const governance = computed(() => store.config?.governance);
const namespaces = computed(() => governance.value?.namespaces || []);
const metrics = computed(() => {
  const totals = store.summary?.totals || {};
  return [
    { label: 'namespace', value: namespaces.value.length },
    { label: 'governance reject', value: totals.governanceRejectTotal || 0, tone: 'warn' as const },
    { label: 'namespace limit', value: totals.namespaceLimitRejectTotal || 0, tone: 'warn' as const },
    { label: 'key governance', value: totals.keyGovernanceRejectTotal || 0, tone: 'warn' as const }
  ];
});

onMounted(async () => {
  await store.loadConfigDomain();
  await store.loadObservability();
});
</script>

<template>
  <div v-loading="store.loading">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-tag :type="governance?.enabled ? 'success' : 'info'">enabled={{ governance?.enabled ?? false }}</el-tag>
        <el-tag :type="governance?.requireAuth ? 'warning' : 'info'">requireAuth={{ governance?.requireAuth ?? false }}</el-tag>
      </div>
      <el-button :icon="Refresh" @click="store.loadConfigDomain()">刷新</el-button>
    </div>

    <MetricStrip class="section" :items="metrics" />

    <section class="grid-2 section">
      <div class="panel">
        <div class="panel-header"><h2>Namespace 治理</h2></div>
        <div class="panel-body">
          <el-table :data="namespaces" size="small">
            <el-table-column prop="name" label="namespace" width="130" />
            <el-table-column label="token" width="90"><template #default>******</template></el-table-column>
            <el-table-column prop="readOnly" label="readOnly" width="100" />
            <el-table-column label="prefixes" show-overflow-tooltip>
              <template #default="{ row }">{{ row.allowedKeyPrefixes?.join(', ') || '-' }}</template>
            </el-table-column>
            <el-table-column label="limits" width="180">
              <template #default="{ row }">
                c={{ row.limits?.maxConnections || 0 }} qps={{ row.limits?.maxQps || 0 }} in={{ row.limits?.maxInflight || 0 }}
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header"><h2>命令策略</h2></div>
        <div class="panel-body">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="Denied">
              {{ governance?.commandPolicy?.deniedCommands?.join(', ') || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="WarnOnly">
              {{ governance?.commandPolicy?.warnOnlyCommands?.join(', ') || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="Key Window">
              {{ governance?.keyLimitWindowMillis || '-' }}ms / {{ governance?.keyLimitBucketMillis || '-' }}ms
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </div>
    </section>

    <section class="panel">
      <div class="panel-header"><h2>Key Rules</h2></div>
      <div class="panel-body">
        <el-table :data="namespaces.flatMap((ns) => (ns.keyRules || []).map((rule) => ({ namespace: ns.name, ...rule })))" size="small">
          <el-table-column prop="namespace" label="namespace" width="130" />
          <el-table-column prop="name" label="rule" />
          <el-table-column prop="keyPrefix" label="keyPrefix" show-overflow-tooltip />
          <el-table-column prop="hashTag" label="hashTag" width="140" />
          <el-table-column prop="disabled" label="disabled" width="100" />
          <el-table-column prop="maxQps" label="maxQps" width="100" />
        </el-table>
      </div>
    </section>

    <section class="panel section">
      <div class="panel-header"><h2>掩码配置快照</h2></div>
      <div class="panel-body">
        <pre class="code-block">{{ JSON.stringify(maskTokens(store.config || {}), null, 2) }}</pre>
      </div>
    </section>
  </div>
</template>
