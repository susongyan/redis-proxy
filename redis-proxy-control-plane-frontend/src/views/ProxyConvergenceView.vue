<script setup lang="ts">
import { Delete, Refresh } from '@element-plus/icons-vue';
import { ElMessageBox } from 'element-plus';
import { onMounted } from 'vue';
import { api } from '../api/client';
import ConvergenceStatusHelp from '../components/ConvergenceStatusHelp.vue';
import StatusTag from '../components/StatusTag.vue';
import { useControlPlaneStore } from '../stores/controlPlane';
import { compactHash, convergenceTone, formatDateTime } from '../utils/status';

const store = useControlPlaneStore();

async function load() {
  await store.loadObservability();
  await store.refreshConvergence();
}

async function remove(proxyId: string) {
  await ElMessageBox.confirm(`确认删除 target ${proxyId}`, '删除 target', { type: 'warning' });
  await api.observability.deleteTarget(proxyId);
  await load();
}

onMounted(load);
</script>

<template>
  <div v-loading="store.loading">
    <div class="toolbar">
      <div class="toolbar-left">
        <StatusTag :label="store.convergence?.status" :type="convergenceTone(store.convergence?.status)" />
        <ConvergenceStatusHelp />
        <span class="subtle">expectedRouteEpoch={{ store.convergence?.expectedRouteEpoch || '-' }}</span>
        <span class="subtle">expectedHash={{ compactHash(store.convergence?.expectedConfigHash) }}</span>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <section class="panel">
      <div class="panel-header">
        <div class="title-with-help">
          <h2>实例收敛明细</h2>
          <ConvergenceStatusHelp />
        </div>
      </div>
      <div class="panel-body">
        <el-table :data="store.convergence?.proxies || []" size="small">
          <el-table-column prop="proxyId" label="proxyId" min-width="150" />
          <el-table-column prop="group" label="group" width="120" />
          <el-table-column prop="advertiseIp" label="advertiseIp" width="140" />
          <el-table-column prop="advertisePort" label="dataPort" width="100" />
          <el-table-column prop="dataplane" label="dataplane" width="110" />
          <el-table-column prop="adminUrl" label="adminUrl" min-width="220" show-overflow-tooltip />
          <el-table-column label="heartbeat" width="190">
            <template #default="{ row }">{{ formatDateTime(row.lastPollTime || row.collectedAt) }}</template>
          </el-table-column>
          <el-table-column prop="epoch" label="epoch" width="90" />
          <el-table-column label="hash" width="180">
            <template #default="{ row }">{{ compactHash(row.configHash) }}</template>
          </el-table-column>
          <el-table-column prop="lastApplyResult" label="apply" width="130" />
          <el-table-column label="pollTime" width="190">
            <template #default="{ row }">{{ formatDateTime(row.lastPollTime) }}</template>
          </el-table-column>
          <el-table-column width="130">
            <template #header>
              <span class="column-header-help">status <ConvergenceStatusHelp /></span>
            </template>
            <template #default="{ row }"><StatusTag :label="row.status" :type="convergenceTone(row.status)" /></template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button size="small" type="danger" :icon="Delete" @click="remove(row.proxyId)" />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>
  </div>
</template>
