<script setup lang="ts">
import * as echarts from 'echarts';
import { Refresh } from '@element-plus/icons-vue';
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import ConvergenceStatusHelp from '../components/ConvergenceStatusHelp.vue';
import MetricStrip from '../components/MetricStrip.vue';
import StatusTag from '../components/StatusTag.vue';
import { useControlPlaneStore } from '../stores/controlPlane';
import { compactHash, convergenceTone, formatDateTime, planTone } from '../utils/status';

const store = useControlPlaneStore();
const chartEl = ref<HTMLDivElement>();

const metrics = computed(() => {
  const totals = store.summary?.totals || {};
  return [
    { label: 'Route Epoch', value: store.routeStatus?.routeEpoch ?? '-', tone: 'default' as const },
    { label: 'Proxy 收敛', value: store.convergence?.status || '-', tone: store.convergence?.status === 'CONVERGED' ? 'good' as const : 'warn' as const },
    { label: '治理拒绝', value: Number(totals.governanceRejectTotal || 0) + Number(totals.keyGovernanceRejectTotal || 0), tone: 'warn' as const },
    { label: '大响应命中', value: Number(totals.largeResponseTotal || 0), tone: Number(totals.largeResponseTotal || 0) > 0 ? 'bad' as const : 'default' as const }
  ];
});

const activePlans = computed(() => store.plans.filter((plan) => !['COMPLETED', 'ROLLED_BACK', 'CANCELLED'].includes(plan.status)));

function renderChart() {
  if (!chartEl.value) return;
  const totals = store.summary?.totals || {};
  const chart = echarts.init(chartEl.value);
  chart.setOption({
    backgroundColor: 'transparent',
    grid: { left: 36, right: 12, top: 22, bottom: 32 },
    tooltip: { backgroundColor: '#151c2c', borderColor: '#2a3654', textStyle: { color: '#e2e9f4' } },
    xAxis: {
      type: 'category',
      data: ['Auth', 'Governance', 'Namespace', 'KeyRule', 'Hot', 'Large', 'Slow'],
      axisLine: { lineStyle: { color: '#2a3654' } },
      axisLabel: { color: '#95a2b6' }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.12)' } },
      axisLabel: { color: '#95a2b6' }
    },
    series: [
      {
        type: 'bar',
        itemStyle: { color: '#5aa9cf', borderRadius: [4, 4, 0, 0] },
        data: [
          totals.authTotal || 0,
          totals.governanceRejectTotal || 0,
          totals.namespaceLimitRejectTotal || 0,
          totals.keyGovernanceRejectTotal || 0,
          totals.hotKeyObservedTotal || 0,
          totals.largeKeyObservedTotal || 0,
          totals.slowQueryObservedTotal || 0
        ]
      }
    ]
  });
}

onMounted(async () => {
  await store.loadOverview();
  await nextTick();
  renderChart();
});

watch(() => store.summary, () => nextTick(renderChart), { deep: true });
</script>

<template>
  <div v-loading="store.loading">
    <section class="hero-band">
      <div>
        <h2>Redis Proxy 运维控制台</h2>
        <p>聚合配置期望态、数据面收敛、治理拒绝与访问特征，面向灰度发布和集群迁移值守。</p>
      </div>
      <div class="topbar-actions">
        <StatusTag :label="store.convergence?.status" :type="convergenceTone(store.convergence?.status)" />
        <el-tag type="info">epoch {{ store.routeStatus?.routeEpoch || '-' }}</el-tag>
      </div>
    </section>

    <div class="toolbar">
      <div class="toolbar-left">
        <el-alert v-if="store.error" :title="store.error" type="error" show-icon />
      </div>
      <div class="toolbar-right">
        <el-button :icon="Refresh" @click="store.loadOverview()">刷新</el-button>
      </div>
    </div>

    <section class="section">
      <MetricStrip :items="metrics" />
    </section>

    <section class="grid-2 section">
      <div class="panel dark-panel">
        <div class="panel-header">
          <div class="title-with-help">
            <h2>路由期望态</h2>
            <ConvergenceStatusHelp />
          </div>
          <StatusTag :label="store.convergence?.status" :type="convergenceTone(store.convergence?.status)" />
        </div>
        <div class="panel-body">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="defaultCluster">{{ store.routeStatus?.defaultCluster || '-' }}</el-descriptions-item>
            <el-descriptions-item label="expectedVersionId">{{ store.routeStatus?.expectedVersionId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="expectedRouteEpoch">{{ store.routeStatus?.expectedRouteEpoch || '-' }}</el-descriptions-item>
            <el-descriptions-item label="configHash">{{ compactHash(store.routeStatus?.expectedConfigHash) }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="store.convergence?.proxies || []" size="small" style="margin-top: 14px">
            <el-table-column prop="proxyId" label="proxyId" />
            <el-table-column prop="group" label="group" width="110" />
            <el-table-column prop="dataplane" label="data plane" width="110" />
            <el-table-column prop="epoch" label="epoch" width="90" />
            <el-table-column width="120">
              <template #header>
                <span class="column-header-help">status <ConvergenceStatusHelp /></span>
              </template>
              <template #default="{ row }"><StatusTag :label="row.status" :type="convergenceTone(row.status)" /></template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <div class="panel dark-panel">
        <div class="panel-header">
          <h2>治理观测</h2>
          <span class="subtle">最近采集快照</span>
        </div>
        <div class="panel-body">
          <div ref="chartEl" class="chart"></div>
        </div>
      </div>
    </section>

    <section class="grid-3 section">
      <div class="panel">
        <div class="panel-header"><h2>活跃切换计划</h2></div>
        <div class="panel-body">
          <el-table :data="activePlans" size="small" empty-text="无活跃计划">
            <el-table-column prop="planId" label="ID" width="70" />
            <el-table-column prop="sourceCluster" label="source" />
            <el-table-column prop="targetCluster" label="target" />
            <el-table-column label="status" width="120">
              <template #default="{ row }"><StatusTag :label="row.status" :type="planTone(row.status)" /></template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header"><h2>热 Key TopN</h2></div>
        <div class="panel-body">
          <el-table :data="store.hotKeys" size="small" empty-text="无数据">
            <el-table-column prop="key" label="key" show-overflow-tooltip />
            <el-table-column prop="count" label="count" width="90" />
          </el-table>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header"><h2>慢查询 TopN</h2></div>
        <div class="panel-body">
          <el-table :data="store.slowQueries" size="small" empty-text="无数据">
            <el-table-column prop="key" label="key" show-overflow-tooltip />
            <el-table-column prop="maxEndToEndMillis" label="e2e ms" width="100" />
          </el-table>
          <p class="subtle">刷新时间：{{ formatDateTime(store.targets[0]?.lastCollectedAt) }}</p>
        </div>
      </div>
    </section>
  </div>
</template>
