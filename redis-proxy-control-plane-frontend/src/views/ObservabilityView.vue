<script setup lang="ts">
import * as echarts from 'echarts';
import { Refresh } from '@element-plus/icons-vue';
import { nextTick, onMounted, ref } from 'vue';
import MetricStrip from '../components/MetricStrip.vue';
import { api } from '../api/client';
import { useControlPlaneStore } from '../stores/controlPlane';
import { formatDateTime } from '../utils/status';

const store = useControlPlaneStore();
const chartEl = ref<HTMLDivElement>();
const metric = ref('redis_proxy_control_plane_governance_reject_total');

async function load() {
  await store.loadObservability();
  const history = await api.observability.history({ metric: metric.value, stepSeconds: 60 });
  await nextTick();
  if (chartEl.value) {
    const chart = echarts.init(chartEl.value);
    chart.setOption({
      backgroundColor: 'transparent',
      grid: { left: 48, right: 16, top: 24, bottom: 42 },
      tooltip: { trigger: 'axis', backgroundColor: '#0f172a', borderColor: '#334155', textStyle: { color: '#e5eefb' } },
      xAxis: {
        type: 'category',
        data: history.points.map((point) => formatDateTime(point.timestamp)),
        axisLine: { lineStyle: { color: '#334155' } },
        axisLabel: { color: '#9fb0c8' }
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.14)' } },
        axisLabel: { color: '#9fb0c8' }
      },
      series: [{ type: 'line', smooth: true, data: history.points.map((point) => point.value), itemStyle: { color: '#22c55e' }, areaStyle: { color: 'rgba(34, 197, 94, 0.12)' } }]
    });
  }
}

onMounted(load);
</script>

<template>
  <div v-loading="store.loading">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-select v-model="metric" style="width: 360px" @change="load">
          <el-option label="治理拒绝" value="redis_proxy_control_plane_governance_reject_total" />
          <el-option label="Key 治理拒绝" value="redis_proxy_control_plane_key_governance_reject_total" />
          <el-option label="大响应" value="redis_proxy_control_plane_large_response_total" />
          <el-option label="慢查询" value="redis_proxy_control_plane_slow_query_observed_total" />
        </el-select>
        <el-link :href="api.observability.prometheusUrl" target="_blank">Prometheus 聚合出口</el-link>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <MetricStrip
      class="section"
      :items="[
        { label: 'hot tracked', value: store.summary?.totals.hotKeyTracked || 0 },
        { label: 'large tracked', value: store.summary?.totals.largeKeyTracked || 0 },
        { label: 'slow tracked', value: store.summary?.totals.slowQueryTracked || 0 },
        { label: 'targets', value: store.summary?.targets.length || 0 }
      ]"
    />

    <section class="panel dark-panel section">
      <div class="panel-header"><h2>历史趋势</h2></div>
      <div class="panel-body"><div ref="chartEl" class="chart"></div></div>
    </section>

    <section class="grid-3">
      <div class="panel">
        <div class="panel-header"><h2>Hot Keys</h2></div>
        <div class="panel-body">
          <el-table :data="store.hotKeys" size="small">
            <el-table-column prop="namespace" label="ns" width="90" />
            <el-table-column prop="command" label="cmd" width="80" />
            <el-table-column prop="key" label="key" show-overflow-tooltip />
            <el-table-column prop="count" label="count" width="80" />
          </el-table>
        </div>
      </div>
      <div class="panel">
        <div class="panel-header"><h2>Large Keys</h2></div>
        <div class="panel-body">
          <el-table :data="store.largeKeys" size="small">
            <el-table-column prop="namespace" label="ns" width="90" />
            <el-table-column prop="key" label="key" show-overflow-tooltip />
            <el-table-column prop="maxResponseBytes" label="resp" width="90" />
          </el-table>
        </div>
      </div>
      <div class="panel">
        <div class="panel-header"><h2>Slow Queries</h2></div>
        <div class="panel-body">
          <el-table :data="store.slowQueries" size="small">
            <el-table-column prop="namespace" label="ns" width="90" />
            <el-table-column prop="key" label="key" show-overflow-tooltip />
            <el-table-column prop="maxEndToEndMillis" label="e2e" width="80" />
          </el-table>
        </div>
      </div>
    </section>
  </div>
</template>
