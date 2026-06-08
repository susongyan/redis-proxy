<script setup lang="ts">
import { Delete, Edit, Refresh, Switch, View } from '@element-plus/icons-vue';
import { ElMessageBox } from 'element-plus';
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../api/client';
import type { GroupRouteStatus, RouteConvergenceInstance, TargetStatus } from '../api/types';
import ConvergenceStatusHelp from '../components/ConvergenceStatusHelp.vue';
import MetricStrip from '../components/MetricStrip.vue';
import StatusTag from '../components/StatusTag.vue';
import { useControlPlaneStore } from '../stores/controlPlane';
import { compactHash, convergenceTone, formatDateTime } from '../utils/status';

interface GroupConvergenceSummary {
  group: string;
  status: string;
  total: number;
  converged: number;
  stale: number;
  drift: number;
  unreachable: number;
  go: number;
  java: number;
}

interface RouteRuntimeGroup extends GroupRouteStatus {
  convergence: GroupConvergenceSummary;
}

interface ClusterRouteRule {
  group: string;
  name: string;
  trafficPercent?: number;
  matchAll?: boolean;
  namespace?: string;
  keyPrefix?: string;
  keyPattern?: string;
  hashTag?: string;
}

interface ClusterRouteSummary {
  cluster: string;
  visibleGroups: string[];
  defaultGroups: string[];
  ruleGroups: string[];
  rules: ClusterRouteRule[];
  proxyTotal: number;
  convergedProxies: number;
}

interface ProxyRow extends RouteConvergenceInstance {
  target?: TargetStatus;
}

type MetricItem = { label: string; value: string | number; tone?: 'default' | 'good' | 'warn' | 'bad' };

const store = useControlPlaneStore();
const route = useRoute();
const router = useRouter();
const activeTab = ref('groups');
const selectedGroupName = ref('');
const selectedClusterName = ref('');
const selectedStatus = ref('ALL');
const selectedProxyId = ref('');
const detailVisible = ref(false);

const fallbackRouteGroup = computed<GroupRouteStatus>(() => ({
  group: 'default',
  expectedVersionId: store.routeStatus?.expectedVersionId || store.routeStatus?.currentVersionId || 0,
  expectedRouteEpoch: store.routeStatus?.expectedRouteEpoch || store.routeStatus?.routeEpoch || 0,
  expectedConfigHash: store.routeStatus?.expectedConfigHash || '',
  defaultCluster: store.routeStatus?.defaultCluster || '-',
  clusters: store.routeStatus?.clusters || [],
  rules: store.routeStatus?.rules || []
}));

const targetByProxyId = computed(() => {
  const out = new Map<string, TargetStatus>();
  for (const target of store.targets) {
    out.set(target.proxyId, target);
  }
  return out;
});

const proxyRows = computed<ProxyRow[]>(() =>
  (store.convergence?.proxies || []).map((proxy) => ({
    ...proxy,
    target: targetByProxyId.value.get(proxy.proxyId)
  }))
);

const routeGroups = computed<GroupRouteStatus[]>(() => {
  if (store.routeStatus?.groups?.length) {
    return store.routeStatus.groups;
  }
  return [fallbackRouteGroup.value];
});

const convergenceByGroup = computed(() => {
  const grouped = new Map<string, ProxyRow[]>();
  for (const proxy of proxyRows.value) {
    const group = proxy.group || 'default';
    const proxies = grouped.get(group) || [];
    proxies.push(proxy);
    grouped.set(group, proxies);
  }

  const summaries = new Map<string, GroupConvergenceSummary>();
  for (const [group, proxies] of grouped) {
    summaries.set(group, summarizeGroup(group, proxies));
  }
  return summaries;
});

const routeRuntimeGroups = computed<RouteRuntimeGroup[]>(() => {
  const groups = new Map<string, GroupRouteStatus>();
  const hasExplicitGroups = Boolean(store.routeStatus?.groups?.length);
  if (hasExplicitGroups || convergenceByGroup.value.size === 0) {
    for (const group of routeGroups.value) {
      groups.set(group.group, group);
    }
  }
  for (const group of convergenceByGroup.value.keys()) {
    if (!groups.has(group)) {
      groups.set(group, { ...fallbackRouteGroup.value, group });
    }
  }
  return [...groups.values()].map((group) => ({
    ...group,
    convergence: convergenceByGroup.value.get(group.group) || emptyConvergence(group.group)
  }));
});

const selectedGroup = computed<RouteRuntimeGroup | undefined>(
  () => routeRuntimeGroups.value.find((group) => group.group === selectedGroupName.value) || routeRuntimeGroups.value[0]
);
const selectedRules = computed(() => selectedGroup.value?.rules || []);
const selectedClusters = computed(() => selectedGroup.value?.clusters || []);
const selectedGroupProxies = computed(() =>
  proxyRows.value.filter((proxy) => (proxy.group || 'default') === (selectedGroup.value?.group || 'default'))
);

const clusterRoutes = computed<ClusterRouteSummary[]>(() => {
  const clusters = new Map<string, ClusterRouteSummary>();
  for (const group of routeRuntimeGroups.value) {
    for (const cluster of group.clusters) {
      const summary = ensureClusterSummary(clusters, cluster);
      summary.visibleGroups.push(group.group);
      summary.proxyTotal += group.convergence.total;
      summary.convergedProxies += group.convergence.converged;
    }
    if (group.defaultCluster && group.defaultCluster !== '-') {
      ensureClusterSummary(clusters, group.defaultCluster).defaultGroups.push(group.group);
    }
    for (const rule of group.rules) {
      if (!rule.cluster) continue;
      const summary = ensureClusterSummary(clusters, rule.cluster);
      summary.ruleGroups.push(group.group);
      summary.rules.push({
        group: group.group,
        name: rule.name,
        trafficPercent: rule.trafficPercent,
        matchAll: rule.matchAll,
        namespace: rule.namespace,
        keyPrefix: rule.keyPrefix,
        keyPattern: rule.keyPattern,
        hashTag: rule.hashTag
      });
    }
  }
  return [...clusters.values()].map((summary) => ({
    ...summary,
    visibleGroups: unique(summary.visibleGroups),
    defaultGroups: unique(summary.defaultGroups),
    ruleGroups: unique(summary.ruleGroups)
  }));
});

const selectedCluster = computed<ClusterRouteSummary | undefined>(
  () => clusterRoutes.value.find((cluster) => cluster.cluster === selectedClusterName.value) || clusterRoutes.value[0]
);

const groupOptions = computed(() => ['ALL', ...routeRuntimeGroups.value.map((group) => group.group)]);
const statusOptions = ['ALL', 'CONVERGED', 'STALE', 'DRIFT', 'UNREACHABLE'];
const filteredProxyRows = computed(() =>
  proxyRows.value.filter((row) => {
    const groupMatched = selectedGroupName.value === '' || selectedGroupName.value === 'ALL' || (row.group || 'default') === selectedGroupName.value;
    const statusMatched = selectedStatus.value === 'ALL' || row.status === selectedStatus.value;
    return groupMatched && statusMatched;
  })
);

const selectedProxy = computed(() => proxyRows.value.find((row) => row.proxyId === selectedProxyId.value) || proxyRows.value[0]);

const metrics = computed<MetricItem[]>(() => [
  { label: 'expectedVersion', value: store.convergence?.expectedVersionId || store.routeStatus?.expectedVersionId || '-' },
  { label: 'routeEpoch', value: store.convergence?.expectedRouteEpoch || store.routeStatus?.expectedRouteEpoch || '-' },
  {
    label: '整体收敛',
    value: store.convergence?.status || '-',
    tone: store.convergence?.status === 'CONVERGED' ? 'good' : 'warn'
  },
  {
    label: '异常实例',
    value: Number(store.convergence?.drift || 0) + Number(store.convergence?.stale || 0) + Number(store.convergence?.unreachable || 0),
    tone:
      Number(store.convergence?.drift || 0) + Number(store.convergence?.stale || 0) + Number(store.convergence?.unreachable || 0) > 0
        ? 'bad'
        : 'default'
  }
]);

async function load() {
  await Promise.all([store.loadConfigDomain(), store.loadObservability()]);
  await store.refreshConvergence();
  syncSelections();
}

async function remove(proxyId: string) {
  await ElMessageBox.confirm(`确认删除 target ${proxyId}`, '删除 target', { type: 'warning' });
  await api.observability.deleteTarget(proxyId);
  await load();
}

function selectGroup(group: string) {
  selectedGroupName.value = group;
}

function selectCluster(cluster: string) {
  selectedClusterName.value = cluster;
}

function selectProxy(row: ProxyRow) {
  selectedProxyId.value = row.proxyId;
  detailVisible.value = true;
}

function go(path: string) {
  router.push(path);
}

function goTab(tab: string) {
  activeTab.value = tab;
  router.replace({ path: '/routing', query: { ...route.query, tab } });
}

function targetCluster(row?: ProxyRow) {
  return row?.target?.cluster || row?.target?.resourceAttributes?.['redis.proxy.cluster'] || '-';
}

function heartbeatAge(row?: ProxyRow) {
  if (!row?.target?.lastHeartbeatAt) return '-';
  const time = new Date(row.target.lastHeartbeatAt).getTime();
  if (Number.isNaN(time)) return '-';
  const seconds = Math.max(0, Math.round((Date.now() - time) / 1000));
  return `${seconds}s`;
}

function emptyConvergence(group: string): GroupConvergenceSummary {
  return {
    group,
    status: 'UNREGISTERED',
    total: 0,
    converged: 0,
    stale: 0,
    drift: 0,
    unreachable: 0,
    go: 0,
    java: 0
  };
}

function summarizeGroup(group: string, proxies: ProxyRow[]): GroupConvergenceSummary {
  const summary = emptyConvergence(group);
  summary.total = proxies.length;
  for (const proxy of proxies) {
    if (proxy.status === 'CONVERGED') summary.converged += 1;
    if (proxy.status === 'STALE') summary.stale += 1;
    if (proxy.status === 'DRIFT') summary.drift += 1;
    if (proxy.status === 'UNREACHABLE') summary.unreachable += 1;
    if (proxy.dataplane === 'go') summary.go += 1;
    if (proxy.dataplane === 'java') summary.java += 1;
  }
  summary.status = mergeStatus(summary);
  return summary;
}

function mergeStatus(summary: GroupConvergenceSummary) {
  if (summary.total === 0) return 'UNREGISTERED';
  if (summary.unreachable > 0) return 'UNREACHABLE';
  if (summary.drift > 0) return 'DRIFT';
  if (summary.stale > 0) return 'STALE';
  if (summary.converged === summary.total) return 'CONVERGED';
  return 'PARTIAL';
}

function ensureClusterSummary(clusters: Map<string, ClusterRouteSummary>, cluster: string) {
  let summary = clusters.get(cluster);
  if (!summary) {
    summary = {
      cluster,
      visibleGroups: [],
      defaultGroups: [],
      ruleGroups: [],
      rules: [],
      proxyTotal: 0,
      convergedProxies: 0
    };
    clusters.set(cluster, summary);
  }
  return summary;
}

function unique(values: string[]) {
  return [...new Set(values)];
}

function syncSelections() {
  const queryTab = String(route.query.tab || 'groups');
  activeTab.value = ['groups', 'clusters', 'instances'].includes(queryTab) ? queryTab : 'groups';
  if (!routeRuntimeGroups.value.some((group) => group.group === selectedGroupName.value)) {
    selectedGroupName.value = routeRuntimeGroups.value[0]?.group || '';
  }
  if (!clusterRoutes.value.some((cluster) => cluster.cluster === selectedClusterName.value)) {
    selectedClusterName.value = clusterRoutes.value[0]?.cluster || '';
  }
  if (!proxyRows.value.some((row) => row.proxyId === selectedProxyId.value)) {
    selectedProxyId.value = proxyRows.value[0]?.proxyId || '';
  }
}

watch(() => route.query.tab, syncSelections);
watch(routeRuntimeGroups, syncSelections);
watch(clusterRoutes, syncSelections);
watch(proxyRows, syncSelections);
onMounted(load);
</script>

<template>
  <div v-loading="store.loading">
    <div class="toolbar">
      <div>
        <h2 class="page-section-title">路由与实例收敛</h2>
        <p class="subtle">按 proxy group、Redis cluster 和 proxy instance 查看配置发布后的实际生效状态。</p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <MetricStrip class="section" :items="metrics" />

    <section class="panel section">
      <div class="panel-header">
        <div class="title-with-help">
          <h2>发布期望态</h2>
          <ConvergenceStatusHelp />
        </div>
        <StatusTag :label="store.convergence?.status" :type="convergenceTone(store.convergence?.status)" />
      </div>
      <div class="panel-body">
        <div class="route-detail-grid">
          <div>
            <span>expectedVersionId</span>
            <strong>{{ store.convergence?.expectedVersionId || '-' }}</strong>
          </div>
          <div>
            <span>expectedRouteEpoch</span>
            <strong>{{ store.convergence?.expectedRouteEpoch || '-' }}</strong>
          </div>
          <div>
            <span>expectedConfigHash</span>
            <strong>{{ compactHash(store.convergence?.expectedConfigHash) }}</strong>
          </div>
          <div>
            <span>proxy 收敛</span>
            <strong>{{ store.convergence?.converged || 0 }} / {{ store.convergence?.total || 0 }}</strong>
          </div>
        </div>
      </div>
    </section>

    <el-tabs :model-value="activeTab" class="section" @tab-change="goTab">
      <el-tab-pane label="Proxy Group 路由" name="groups">
        <section class="route-group-grid section">
          <button
            v-for="group in routeRuntimeGroups"
            :key="group.group"
            type="button"
            class="route-group-card"
            :class="{ 'is-active': group.group === selectedGroup?.group }"
            @click="selectGroup(group.group)"
          >
            <span class="route-group-card-header">
              <strong>{{ group.group }}</strong>
              <StatusTag :label="group.convergence.status" :type="convergenceTone(group.convergence.status)" />
            </span>
            <span class="route-group-meta">
              <span>default</span>
              <strong>{{ group.defaultCluster }}</strong>
            </span>
            <span class="route-group-meta">
              <span>clusters / rules</span>
              <strong>{{ group.clusters.length }} / {{ group.rules.length }}</strong>
            </span>
            <span class="route-group-meta">
              <span>proxies</span>
              <strong>{{ group.convergence.converged }} / {{ group.convergence.total }}</strong>
            </span>
            <span class="route-group-meta">
              <span>dataplane</span>
              <strong>go {{ group.convergence.go }} / java {{ group.convergence.java }}</strong>
            </span>
          </button>
        </section>

        <section class="grid-2">
          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>{{ selectedGroup?.group || '-' }} 调度概览</h2>
                <p class="subtle">展示该 group 的路由期望态和数据面收敛情况。</p>
              </div>
              <StatusTag :label="selectedGroup?.convergence.status" :type="convergenceTone(selectedGroup?.convergence.status)" />
            </div>
            <div class="panel-body">
              <div class="route-detail-grid">
                <div>
                  <span>默认集群</span>
                  <strong>{{ selectedGroup?.defaultCluster || '-' }}</strong>
                </div>
                <div>
                  <span>期望版本</span>
                  <strong>{{ selectedGroup?.expectedVersionId || '-' }}</strong>
                </div>
                <div>
                  <span>期望 epoch</span>
                  <strong>{{ selectedGroup?.expectedRouteEpoch || '-' }}</strong>
                </div>
                <div>
                  <span>收敛实例</span>
                  <strong>{{ selectedGroup?.convergence.converged || 0 }} / {{ selectedGroup?.convergence.total || 0 }}</strong>
                </div>
              </div>
              <div class="route-subsection">
                <h3>可见 Redis 集群</h3>
                <div class="cluster-chip-list">
                  <span v-for="cluster in selectedClusters" :key="cluster" class="cluster-chip">{{ cluster }}</span>
                  <span v-if="!selectedClusters.length" class="subtle">暂无集群</span>
                </div>
              </div>
              <div class="route-subsection">
                <h3>调度操作</h3>
                <div class="route-action-row">
                  <el-button :icon="Edit" @click="go('/config')">编辑配置</el-button>
                  <el-button :icon="Switch" type="primary" @click="go('/cluster-switch')">创建集群切换计划</el-button>
                  <el-button :icon="View" @click="goTab('instances')">查看实例明细</el-button>
                </div>
              </div>
            </div>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>路由规则</h2>
                <p class="subtle">只展示当前选中 group 的期望规则。</p>
              </div>
            </div>
            <div class="panel-body">
              <el-table :data="selectedRules" size="small" height="330">
                <el-table-column prop="name" label="name" min-width="150" show-overflow-tooltip />
                <el-table-column prop="cluster" label="cluster" width="120" />
                <el-table-column prop="namespace" label="namespace" width="120" show-overflow-tooltip />
                <el-table-column prop="keyPrefix" label="keyPrefix" width="130" show-overflow-tooltip />
                <el-table-column prop="keyPattern" label="keyPattern" width="140" show-overflow-tooltip />
                <el-table-column prop="hashTag" label="hashTag" width="110" show-overflow-tooltip />
                <el-table-column label="matchAll" width="90">
                  <template #default="{ row }">{{ row.matchAll ? 'yes' : '-' }}</template>
                </el-table-column>
                <el-table-column label="%" width="70">
                  <template #default="{ row }">{{ row.trafficPercent ?? 100 }}</template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </section>

        <section class="panel section">
          <div class="panel-header">
            <div>
              <h2>{{ selectedGroup?.group || '-' }} Proxy 实例摘要</h2>
              <p class="subtle">实例详情已并入本页的 Proxy 实例收敛 tab。</p>
            </div>
          </div>
          <div class="panel-body">
            <el-table :data="selectedGroupProxies" size="small">
              <el-table-column prop="proxyId" label="proxyId" min-width="220" show-overflow-tooltip />
              <el-table-column prop="dataplane" label="dataplane" width="110" />
              <el-table-column prop="epoch" label="epoch" width="90" />
              <el-table-column label="hash" width="150">
                <template #default="{ row }">{{ compactHash(row.configHash) }}</template>
              </el-table-column>
              <el-table-column prop="lastApplyResult" label="apply" width="120" show-overflow-tooltip />
              <el-table-column prop="reason" label="reason" min-width="180" show-overflow-tooltip />
              <el-table-column label="status" width="130">
                <template #default="{ row }"><StatusTag :label="row.status" :type="convergenceTone(row.status)" /></template>
              </el-table-column>
            </el-table>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="Redis 集群路由" name="clusters">
        <section class="route-group-grid section">
          <button
            v-for="cluster in clusterRoutes"
            :key="cluster.cluster"
            type="button"
            class="route-group-card cluster-route-card"
            :class="{ 'is-active': cluster.cluster === selectedCluster?.cluster }"
            @click="selectCluster(cluster.cluster)"
          >
            <span class="route-group-card-header">
              <strong>{{ cluster.cluster }}</strong>
              <StatusTag :label="cluster.defaultGroups.length ? 'DEFAULT' : 'RULED'" type="primary" />
            </span>
            <span class="route-group-meta">
              <span>visible groups</span>
              <strong>{{ cluster.visibleGroups.length }}</strong>
            </span>
            <span class="route-group-meta">
              <span>default groups</span>
              <strong>{{ cluster.defaultGroups.length }}</strong>
            </span>
            <span class="route-group-meta">
              <span>rules</span>
              <strong>{{ cluster.rules.length }}</strong>
            </span>
            <span class="route-group-meta">
              <span>proxies</span>
              <strong>{{ cluster.convergedProxies }} / {{ cluster.proxyTotal }}</strong>
            </span>
          </button>
        </section>

        <section class="grid-2 section">
          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>{{ selectedCluster?.cluster || '-' }} 集群路由摘要</h2>
                <p class="subtle">用于判断该 Redis 集群当前被哪些 group 使用，以及是否承担灰度切流。</p>
              </div>
            </div>
            <div class="panel-body">
              <div class="route-detail-grid">
                <div>
                  <span>可见 group</span>
                  <strong>{{ selectedCluster?.visibleGroups.length || 0 }}</strong>
                </div>
                <div>
                  <span>默认路由 group</span>
                  <strong>{{ selectedCluster?.defaultGroups.length || 0 }}</strong>
                </div>
                <div>
                  <span>规则命中 group</span>
                  <strong>{{ selectedCluster?.ruleGroups.length || 0 }}</strong>
                </div>
                <div>
                  <span>收敛 proxy</span>
                  <strong>{{ selectedCluster?.convergedProxies || 0 }} / {{ selectedCluster?.proxyTotal || 0 }}</strong>
                </div>
              </div>

              <div class="route-subsection">
                <h3>可见 group</h3>
                <div class="cluster-chip-list">
                  <span v-for="group in selectedCluster?.visibleGroups || []" :key="group" class="cluster-chip">{{ group }}</span>
                  <span v-if="!selectedCluster?.visibleGroups.length" class="subtle">暂无 group</span>
                </div>
              </div>
              <div class="route-subsection">
                <h3>默认路由 group</h3>
                <div class="cluster-chip-list">
                  <span v-for="group in selectedCluster?.defaultGroups || []" :key="group" class="cluster-chip">{{ group }}</span>
                  <span v-if="!selectedCluster?.defaultGroups.length" class="subtle">没有 group 默认走该集群</span>
                </div>
              </div>
            </div>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>{{ selectedCluster?.cluster || '-' }} 相关规则</h2>
                <p class="subtle">只展示目标集群为当前 cluster 的路由规则。</p>
              </div>
            </div>
            <div class="panel-body">
              <el-table :data="selectedCluster?.rules || []" size="small" height="300">
                <el-table-column prop="group" label="group" width="120" show-overflow-tooltip />
                <el-table-column prop="name" label="rule" min-width="150" show-overflow-tooltip />
                <el-table-column prop="namespace" label="namespace" width="120" show-overflow-tooltip />
                <el-table-column prop="keyPrefix" label="keyPrefix" width="130" show-overflow-tooltip />
                <el-table-column prop="keyPattern" label="keyPattern" width="140" show-overflow-tooltip />
                <el-table-column prop="hashTag" label="hashTag" width="110" show-overflow-tooltip />
                <el-table-column label="matchAll" width="90">
                  <template #default="{ row }">{{ row.matchAll ? 'yes' : '-' }}</template>
                </el-table-column>
                <el-table-column label="%" width="70">
                  <template #default="{ row }">{{ row.trafficPercent ?? 100 }}</template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="Proxy 实例收敛" name="instances">
        <section class="route-group-grid section">
          <button
            v-for="group in routeRuntimeGroups"
            :key="group.group"
            type="button"
            class="route-group-card"
            :class="{ 'is-active': selectedGroupName === group.group }"
            @click="selectGroup(group.group)"
          >
            <span class="route-group-card-header">
              <strong>{{ group.group }}</strong>
              <StatusTag :label="group.convergence.status" :type="convergenceTone(group.convergence.status)" />
            </span>
            <span class="route-group-meta">
              <span>proxies</span>
              <strong>{{ group.convergence.converged }} / {{ group.convergence.total }}</strong>
            </span>
            <span class="route-group-meta">
              <span>dataplane</span>
              <strong>go {{ group.convergence.go }} / java {{ group.convergence.java }}</strong>
            </span>
            <span class="route-group-meta">
              <span>drift / stale</span>
              <strong>{{ group.convergence.drift }} / {{ group.convergence.stale }}</strong>
            </span>
            <span class="route-group-meta">
              <span>unreachable</span>
              <strong>{{ group.convergence.unreachable }}</strong>
            </span>
          </button>
        </section>

        <section class="panel section">
          <div class="panel-header">
            <h2>实例列表</h2>
            <div class="toolbar-right">
              <el-select v-model="selectedGroupName" size="small" style="width: 150px">
                <el-option v-for="group in groupOptions" :key="group" :label="group === 'ALL' ? '全部 group' : group" :value="group" />
              </el-select>
              <el-select v-model="selectedStatus" size="small" style="width: 150px">
                <el-option v-for="status in statusOptions" :key="status" :label="status === 'ALL' ? '全部状态' : status" :value="status" />
              </el-select>
            </div>
          </div>
          <div class="panel-body">
            <el-table :data="filteredProxyRows" size="small" height="560" highlight-current-row @row-click="selectProxy">
              <el-table-column prop="proxyId" label="proxyId" min-width="260" show-overflow-tooltip />
              <el-table-column prop="group" label="group" width="120" />
              <el-table-column prop="dataplane" label="dataplane" width="110" />
              <el-table-column prop="advertiseIp" label="advertiseIp" width="130" />
              <el-table-column prop="advertisePort" label="dataPort" width="95" />
              <el-table-column prop="adminUrl" label="adminUrl" min-width="210" show-overflow-tooltip />
              <el-table-column label="targetCluster" width="140">
                <template #default="{ row }">{{ targetCluster(row) }}</template>
              </el-table-column>
              <el-table-column prop="epoch" label="epoch" width="85" />
              <el-table-column label="hash" width="165">
                <template #default="{ row }">{{ compactHash(row.configHash) }}</template>
              </el-table-column>
              <el-table-column label="heartbeat age" width="125">
                <template #default="{ row }">{{ heartbeatAge(row) }}</template>
              </el-table-column>
              <el-table-column label="status" width="135">
                <template #header>
                  <span class="column-header-help">status <ConvergenceStatusHelp /></span>
                </template>
                <template #default="{ row }"><StatusTag :label="row.status" :type="convergenceTone(row.status)" /></template>
              </el-table-column>
              <el-table-column label="操作" width="120" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" :icon="View" aria-label="查看实例详情" title="查看实例详情" @click.stop="selectProxy(row)" />
                  <el-button size="small" type="danger" :icon="Delete" aria-label="删除实例" title="删除实例" @click.stop="remove(row.proxyId)" />
                </template>
              </el-table-column>
            </el-table>
          </div>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="detailVisible" width="720px" destroy-on-close>
      <template #header>
        <div class="dialog-title">
          <strong>实例详情</strong>
          <span>{{ selectedProxy?.proxyId || '-' }}</span>
        </div>
      </template>
      <div class="version-config-toolbar">
        <StatusTag :label="selectedProxy?.status" :type="convergenceTone(selectedProxy?.status)" />
      </div>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="proxyId">{{ selectedProxy?.proxyId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="group">{{ selectedProxy?.group || '-' }}</el-descriptions-item>
        <el-descriptions-item label="dataplane">{{ selectedProxy?.dataplane || '-' }}</el-descriptions-item>
        <el-descriptions-item label="advertise">{{ selectedProxy?.advertiseIp || '-' }}:{{ selectedProxy?.advertisePort || '-' }}</el-descriptions-item>
        <el-descriptions-item label="adminUrl">{{ selectedProxy?.adminUrl || '-' }}</el-descriptions-item>
        <el-descriptions-item label="targetCluster">{{ targetCluster(selectedProxy) }}</el-descriptions-item>
        <el-descriptions-item label="epoch">{{ selectedProxy?.epoch || '-' }}</el-descriptions-item>
        <el-descriptions-item label="configHash">{{ selectedProxy?.configHash || '-' }}</el-descriptions-item>
        <el-descriptions-item label="lastApplyResult">{{ selectedProxy?.lastApplyResult || '-' }}</el-descriptions-item>
        <el-descriptions-item label="lastApplyTime">{{ formatDateTime(selectedProxy?.lastApplyTime) }}</el-descriptions-item>
        <el-descriptions-item label="lastPollTime">{{ formatDateTime(selectedProxy?.lastPollTime) }}</el-descriptions-item>
        <el-descriptions-item label="lastHeartbeatAt">{{ formatDateTime(selectedProxy?.target?.lastHeartbeatAt) }}</el-descriptions-item>
        <el-descriptions-item label="lastCollectedAt">{{ formatDateTime(selectedProxy?.target?.lastCollectedAt || selectedProxy?.collectedAt) }}</el-descriptions-item>
        <el-descriptions-item label="heartbeatTtlSeconds">{{ selectedProxy?.target?.heartbeatTtlSeconds || '-' }}</el-descriptions-item>
        <el-descriptions-item label="registrationSource">{{ selectedProxy?.target?.registrationSource || '-' }}</el-descriptions-item>
        <el-descriptions-item label="reason">{{ selectedProxy?.reason || selectedProxy?.target?.lastError || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>
