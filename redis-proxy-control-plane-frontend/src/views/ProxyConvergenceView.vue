<script setup lang="ts">
import { Delete, Plus, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { onMounted, reactive } from 'vue';
import { api } from '../api/client';
import StatusTag from '../components/StatusTag.vue';
import { useControlPlaneStore } from '../stores/controlPlane';
import { compactHash, convergenceTone, formatDateTime } from '../utils/status';

const store = useControlPlaneStore();
const target = reactive({
  proxyId: '',
  adminUrl: 'http://127.0.0.1:8080',
  dataplane: 'go',
  cluster: '',
  pollIntervalSeconds: 5
});

async function load() {
  await store.loadObservability();
  await store.refreshConvergence();
}

async function register() {
  await api.observability.registerTarget({ ...target });
  ElMessage.success('target 已注册');
  await load();
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
        <span class="subtle">expectedRouteEpoch={{ store.convergence?.expectedRouteEpoch || '-' }}</span>
        <span class="subtle">expectedHash={{ compactHash(store.convergence?.expectedConfigHash) }}</span>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <section class="panel section">
      <div class="panel-header"><h2>注册 Proxy Target</h2></div>
      <div class="panel-body">
        <el-form :inline="true" :model="target">
          <el-form-item label="proxyId"><el-input v-model="target.proxyId" /></el-form-item>
          <el-form-item label="adminUrl"><el-input v-model="target.adminUrl" style="width: 260px" /></el-form-item>
          <el-form-item label="dataplane">
            <el-select v-model="target.dataplane" style="width: 110px">
              <el-option label="go" value="go" />
              <el-option label="java" value="java" />
            </el-select>
          </el-form-item>
          <el-form-item label="cluster"><el-input v-model="target.cluster" style="width: 140px" /></el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Plus" @click="register">注册</el-button>
          </el-form-item>
        </el-form>
      </div>
    </section>

    <section class="panel">
      <div class="panel-header"><h2>实例收敛明细</h2></div>
      <div class="panel-body">
        <el-table :data="store.convergence?.proxies || []" size="small">
          <el-table-column prop="proxyId" label="proxyId" min-width="150" />
          <el-table-column prop="dataplane" label="dataplane" width="110" />
          <el-table-column prop="adminUrl" label="adminUrl" min-width="220" show-overflow-tooltip />
          <el-table-column prop="epoch" label="epoch" width="90" />
          <el-table-column label="hash" width="180">
            <template #default="{ row }">{{ compactHash(row.configHash) }}</template>
          </el-table-column>
          <el-table-column prop="lastApplyResult" label="apply" width="130" />
          <el-table-column label="pollTime" width="190">
            <template #default="{ row }">{{ formatDateTime(row.lastPollTime) }}</template>
          </el-table-column>
          <el-table-column label="status" width="130">
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
