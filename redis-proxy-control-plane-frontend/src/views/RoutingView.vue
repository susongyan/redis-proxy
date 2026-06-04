<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import { onMounted } from 'vue';
import ConvergenceStatusHelp from '../components/ConvergenceStatusHelp.vue';
import MetricStrip from '../components/MetricStrip.vue';
import StatusTag from '../components/StatusTag.vue';
import { useControlPlaneStore } from '../stores/controlPlane';
import { compactHash, convergenceTone } from '../utils/status';

const store = useControlPlaneStore();

onMounted(() => store.loadConfigDomain());
</script>

<template>
  <div v-loading="store.loading">
    <div class="toolbar">
      <div></div>
      <el-button :icon="Refresh" @click="store.loadConfigDomain()">刷新</el-button>
    </div>

    <MetricStrip
      class="section"
      :items="[
        { label: 'defaultCluster', value: store.routeStatus?.defaultCluster || '-' },
        { label: 'routeEpoch', value: store.routeStatus?.routeEpoch || '-' },
        { label: 'configHash', value: compactHash(store.routeStatus?.expectedConfigHash) },
        { label: 'clusters', value: store.routeStatus?.clusters?.length || 0 }
      ]"
    />

    <section class="panel section">
      <div class="panel-header">
        <div class="title-with-help">
          <h2>收敛状态</h2>
          <ConvergenceStatusHelp />
        </div>
        <StatusTag :label="store.convergence?.status" :type="convergenceTone(store.convergence?.status)" />
      </div>
      <div class="panel-body">
        <el-table :data="store.convergence?.proxies || []" size="small">
          <el-table-column prop="proxyId" label="proxyId" />
          <el-table-column prop="group" label="group" width="120" />
          <el-table-column prop="dataplane" label="dataplane" width="110" />
          <el-table-column prop="epoch" label="epoch" width="90" />
          <el-table-column label="hash" width="180">
            <template #default="{ row }">{{ compactHash(row.configHash) }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="reason" show-overflow-tooltip />
          <el-table-column width="120">
            <template #header>
              <span class="column-header-help">status <ConvergenceStatusHelp /></span>
            </template>
            <template #default="{ row }"><StatusTag :label="row.status" :type="convergenceTone(row.status)" /></template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <section class="grid-2">
      <div class="panel">
        <div class="panel-header"><h2>路由规则</h2></div>
        <div class="panel-body">
          <el-table :data="store.routeStatus?.rules || []" size="small">
            <el-table-column prop="name" label="name" min-width="160" />
            <el-table-column prop="cluster" label="cluster" width="120" />
            <el-table-column prop="namespace" label="namespace" width="120" />
            <el-table-column prop="keyPrefix" label="keyPrefix" width="140" show-overflow-tooltip />
            <el-table-column prop="keyPattern" label="keyPattern" width="150" show-overflow-tooltip />
            <el-table-column prop="hashTag" label="hashTag" width="110" />
            <el-table-column prop="matchAll" label="matchAll" width="100" />
            <el-table-column prop="trafficPercent" label="%" width="80" />
          </el-table>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header"><h2>Backend Clusters</h2></div>
        <div class="panel-body">
          <el-table :data="store.config?.backends?.clusters || []" size="small">
            <el-table-column prop="name" label="cluster" width="140" />
            <el-table-column label="nodes">
              <template #default="{ row }">{{ row.nodes?.join(', ') }}</template>
            </el-table-column>
            <el-table-column label="pool" width="170">
              <template #default="{ row }">
                {{ row.pool?.connectionsPerNode || '-' }} / {{ row.pool?.maxInflightPerConnection || '-' }}
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </section>
  </div>
</template>
