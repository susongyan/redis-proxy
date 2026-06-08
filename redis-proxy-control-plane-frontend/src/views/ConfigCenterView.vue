<script setup lang="ts">
import { Check, Delete, Plus, Refresh, RefreshLeft } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, ref } from 'vue';
import { api } from '../api/client';
import type { Cluster, ConfigDiff, ConfigVersion, KeyRule, Namespace, ProxyConfig, ProxyGroup, RouteRule } from '../api/types';
import ConvergenceStatusHelp from '../components/ConvergenceStatusHelp.vue';
import StatusTag from '../components/StatusTag.vue';
import { useControlPlaneStore } from '../stores/controlPlane';
import { buildSideBySideYamlDiff, buildYamlDiff } from '../utils/configDiff';
import { compactHash, convergenceTone, formatDateTime, maskTokens } from '../utils/status';
import { toYaml } from '../utils/yaml';

type DraftConfig = ProxyConfig & {
  server: { listen?: string };
  admin: { listen?: string };
  backends: { clusters: Cluster[] };
  routing: NonNullable<ProxyConfig['routing']> & { rules: RouteRule[] };
  limits: NonNullable<ProxyConfig['limits']>;
  proxyGroups: ProxyGroup[];
  governance: NonNullable<ProxyConfig['governance']> & {
    commandPolicy: NonNullable<NonNullable<ProxyConfig['governance']>['commandPolicy']>;
    namespaces: Namespace[];
  };
};

const store = useControlPlaneStore();
const activeTab = ref('current');
const editorText = ref('');
const operator = ref('system');
const reason = ref('');
const selectedVersion = ref<number>();
const diffTarget = ref<number>();
const diff = ref<ConfigDiff>();
const draft = ref<DraftConfig>(normalizeConfig());
const waitingConvergence = ref(false);
const publishing = ref(false);
const publishReviewVisible = ref(false);
const activeCollapse = ref(['basic']);
const yamlDrawerVisible = ref(false);
const readonlyViewMode = ref<'json' | 'yaml'>('json');
const versionConfigDialogVisible = ref(false);
const versionConfigViewMode = ref<'json' | 'yaml'>('json');
const viewedVersion = ref<ConfigVersion>();
const viewedVersionDiffTarget = ref<number>();

const isEditing = computed(() => activeTab.value === 'visual' || activeTab.value === 'json');
const yamlPreview = computed(() => toYaml(maskTokens(draft.value)));
const publishDiff = computed(() => buildYamlDiff(store.config || ({} as ProxyConfig), clone(draft.value)));
const publishIssues = computed(() => validateDraft(draft.value));
const publishHasChanges = computed(() => publishDiff.value.stats.added + publishDiff.value.stats.removed > 0);
const currentMaskedJson = computed(() => JSON.stringify(maskTokens(store.config || {}), null, 2));
const currentMaskedYaml = computed(() => toYaml(maskTokens(store.config || {})));
const readonlyConfigText = computed(() => (readonlyViewMode.value === 'yaml' ? currentMaskedYaml.value : currentMaskedJson.value));
const viewedVersionMaskedJson = computed(() => JSON.stringify(maskTokens(viewedVersion.value?.config || {}), null, 2));
const viewedVersionMaskedYaml = computed(() => toYaml(maskTokens(viewedVersion.value?.config || {})));
const viewedVersionText = computed(() => (versionConfigViewMode.value === 'yaml' ? viewedVersionMaskedYaml.value : viewedVersionMaskedJson.value));
const selectedFromVersion = computed(() => store.versions.find((version) => version.versionId === selectedVersion.value));
const selectedToVersion = computed(() => store.versions.find((version) => version.versionId === diffTarget.value));
const latestVersion = computed(() => store.versions.reduce<ConfigVersion | undefined>((latest, version) => (!latest || version.versionId > latest.versionId ? version : latest), undefined));
const sortedVersions = computed(() => [...store.versions].sort((left, right) => right.versionId - left.versionId));
const convergenceGroups = computed(() => {
  const groups = new Map<
    string,
    {
      group: string;
      total: number;
      converged: number;
      stale: number;
      drift: number;
      unreachable: number;
      status: string;
      proxies: NonNullable<typeof store.convergence>['proxies'];
    }
  >();
  for (const proxy of store.convergence?.proxies || []) {
    const groupName = proxy.group || 'default';
    const current = groups.get(groupName) || {
      group: groupName,
      total: 0,
      converged: 0,
      stale: 0,
      drift: 0,
      unreachable: 0,
      status: 'CONVERGED',
      proxies: []
    };
    current.total += 1;
    current.proxies.push(proxy);
    if (proxy.status === 'CONVERGED') current.converged += 1;
    if (proxy.status === 'STALE') current.stale += 1;
    if (proxy.status === 'DRIFT') current.drift += 1;
    if (proxy.status === 'UNREACHABLE') current.unreachable += 1;
    current.status = mergeConvergenceStatus(current.status, proxy.status);
    groups.set(groupName, current);
  }
  return [...groups.values()].sort((left, right) => left.group.localeCompare(right.group));
});
const diffDialogVisible = ref(false);
const diffViewMode = ref<'side-by-side' | 'unified'>('side-by-side');
const visualDiff = computed(() => {
  if (!selectedFromVersion.value || !selectedToVersion.value) return undefined;
  return buildYamlDiff(selectedFromVersion.value.config, selectedToVersion.value.config);
});
const sideBySideDiff = computed(() => {
  if (!selectedFromVersion.value || !selectedToVersion.value) return [];
  return buildSideBySideYamlDiff(selectedFromVersion.value.config, selectedToVersion.value.config);
});

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value ?? {})) as T;
}

function normalizeConfig(input?: ProxyConfig): DraftConfig {
  const config = clone(input || {}) as DraftConfig;
  config.server ||= {};
  config.admin ||= {};
  config.backends ||= { clusters: [] };
  config.backends.clusters ||= [];
  config.backends.clusters.forEach((cluster) => {
    cluster.nodes ||= [];
    cluster.auth ||= { enabled: false, username: '', password: '' };
    cluster.pool ||= {};
  });
  config.routing ||= { defaultCluster: '', routeEpoch: 1, rules: [] };
  config.routing.rules ||= [];
  config.limits ||= {};
  config.analysis ||= {};
  config.proxyGroups ||= [];
  config.proxyGroups.forEach((group) => {
    group.enabledClusters ||= [];
    group.routing ||= {
      defaultCluster: group.enabledClusters[0] || config.routing.defaultCluster || '',
      routeEpoch: config.routing.routeEpoch,
      clusterSlotsRefreshIntervalSeconds: config.routing.clusterSlotsRefreshIntervalSeconds,
      backendAffinityStrategy: config.routing.backendAffinityStrategy || 'client',
      rules: []
    };
    group.routing.rules ||= [];
  });
  config.governance ||= { enabled: false, requireAuth: false, commandPolicy: {}, namespaces: [] };
  config.governance.commandPolicy ||= {};
  config.governance.commandPolicy.deniedCommands ||= [];
  config.governance.commandPolicy.warnOnlyCommands ||= [];
  config.governance.namespaces ||= [];
  config.governance.namespaces.forEach((namespace) => {
    namespace.allowedKeyPrefixes ||= [];
    namespace.deniedCommands ||= [];
    namespace.warnOnlyCommands ||= [];
    namespace.disabledKeys ||= [];
    namespace.keyRules ||= [];
    namespace.limits ||= {};
  });
  return config;
}

function resetEditor() {
  draft.value = normalizeConfig(store.config);
  editorText.value = JSON.stringify(draft.value, null, 2);
}

function startStructuredEdit() {
  resetEditor();
  activeTab.value = 'visual';
}

function startJsonEdit() {
  resetEditor();
  activeTab.value = 'json';
}

function cancelEdit() {
  resetEditor();
  activeTab.value = 'current';
}

async function load() {
  await store.loadConfigDomain();
  resetEditor();
}

function applyJsonToForm() {
  let parsed: unknown;
  try {
    parsed = JSON.parse(editorText.value);
  } catch (error) {
    ElMessage.error(`JSON 解析失败：${errorMessage(error)}`);
    return;
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    ElMessage.error('JSON 顶层必须是一个配置对象');
    return;
  }
  draft.value = normalizeConfig(parsed as ProxyConfig);
  ElMessage.success('JSON 已应用到配置草稿');
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function validateDraft(config: DraftConfig): string[] {
  const issues: string[] = [];
  const clusters = config.backends?.clusters || [];
  const clusterNames = clusters.map((cluster) => cluster.name?.trim()).filter(Boolean) as string[];
  if (!clusters.length) {
    issues.push('至少需要一个 Redis 集群');
  }
  const seenCluster = new Set<string>();
  clusters.forEach((cluster, index) => {
    const label = cluster.name?.trim() || `cluster-${index + 1}`;
    if (!cluster.name?.trim()) {
      issues.push(`第 ${index + 1} 个集群缺少 name`);
    } else if (seenCluster.has(cluster.name.trim())) {
      issues.push(`集群 name 重复：${cluster.name.trim()}`);
    } else {
      seenCluster.add(cluster.name.trim());
    }
    if (!(cluster.nodes || []).filter(Boolean).length) {
      issues.push(`集群 ${label} 至少需要一个 node`);
    }
    if (cluster.auth?.enabled && !cluster.auth.password?.trim()) {
      issues.push(`集群 ${label} 开启认证时 Redis 密码必填`);
    }
  });

  const defaultCluster = config.routing?.defaultCluster?.trim();
  if (!defaultCluster) {
    issues.push('routing.defaultCluster 必填');
  } else if (!clusterNames.includes(defaultCluster)) {
    issues.push(`defaultCluster 引用了不存在的集群：${defaultCluster}`);
  }

  const namespaceNames = new Set(
    (config.governance?.namespaces || []).map((namespace) => namespace.name?.trim()).filter(Boolean) as string[]
  );
  (config.routing?.rules || []).forEach((rule, index) => {
    const label = rule.name?.trim() || `rule-${index + 1}`;
    if (!rule.name?.trim()) {
      issues.push(`第 ${index + 1} 条路由规则缺少 name`);
    }
    if (!rule.cluster?.trim()) {
      issues.push(`路由规则 ${label} 缺少目标 cluster`);
    } else if (!clusterNames.includes(rule.cluster.trim())) {
      issues.push(`路由规则 ${label} 引用了不存在的集群：${rule.cluster}`);
    }
    const percent = rule.trafficPercent ?? 100;
    if (percent < 0 || percent > 100) {
      issues.push(`路由规则 ${label} 的灰度百分比需在 0-100 之间`);
    }
    if (!rule.matchAll && !rule.namespace && !rule.keyPrefix && !rule.keyPattern && !rule.hashTag) {
      issues.push(`路由规则 ${label} 需要设置 matchAll / namespace / keyPrefix / keyPattern / hashTag 之一`);
    }
    if (rule.namespace?.trim() && !namespaceNames.has(rule.namespace.trim())) {
      issues.push(`路由规则 ${label} 引用了不存在的 namespace：${rule.namespace}`);
    }
  });

  const seenNamespace = new Set<string>();
  (config.governance?.namespaces || []).forEach((namespace, index) => {
    const label = namespace.name?.trim() || `namespace-${index + 1}`;
    if (!namespace.name?.trim()) {
      issues.push(`第 ${index + 1} 个 namespace 缺少 name`);
    } else if (seenNamespace.has(namespace.name.trim())) {
      issues.push(`namespace 重复：${namespace.name.trim()}`);
    } else {
      seenNamespace.add(namespace.name.trim());
    }
    if (!namespace.token?.trim()) {
      issues.push(`namespace ${label} 的 token 必填`);
    }
    (namespace.keyRules || []).forEach((rule, ruleIndex) => {
      if (!rule.keyPrefix?.trim() && !rule.hashTag?.trim()) {
        issues.push(`namespace ${label} 第 ${ruleIndex + 1} 条 key rule 需要设置 keyPrefix 或 hashTag`);
      }
    });
  });

  (config.proxyGroups || []).forEach((group, index) => {
    const label = group.name?.trim() || `group-${index + 1}`;
    const enabled = (group.enabledClusters || []).filter(Boolean) as string[];
    enabled.forEach((cluster) => {
      if (!clusterNames.includes(cluster)) {
        issues.push(`分组 ${label} 的 enabledClusters 引用了不存在的集群：${cluster}`);
      }
    });
    const groupDefault = group.routing?.defaultCluster?.trim();
    if (groupDefault && !enabled.includes(groupDefault)) {
      issues.push(`分组 ${label} 的 defaultCluster 必须在 enabledClusters 内`);
    }
    (group.routing?.rules || []).forEach((rule) => {
      if (rule.cluster && !enabled.includes(rule.cluster)) {
        issues.push(`分组 ${label} 规则 ${rule.name || ''} 的 cluster 必须在 enabledClusters 内`);
      }
    });
  });

  return issues;
}

function openPublishReview() {
  publishReviewVisible.value = true;
}

async function confirmPublish() {
  if (publishIssues.value.length) {
    ElMessage.warning('请先修复校验问题再发布');
    return;
  }
  if (!publishHasChanges.value) {
    ElMessage.info('草稿与当前生效配置无差异，无需发布');
    return;
  }
  publishing.value = true;
  try {
    await api.config.publish(clone(draft.value), operator.value, reason.value || 'frontend-publish');
    publishReviewVisible.value = false;
    ElMessage.info('配置已发布，正在等待数据面收敛');
    await load();
    await waitForConvergence();
    activeTab.value = 'current';
  } catch (error) {
    ElMessage.error(`发布失败：${errorMessage(error)}`);
  } finally {
    publishing.value = false;
  }
}

async function waitForConvergence() {
  waitingConvergence.value = true;
  try {
    for (let attempt = 0; attempt < 15; attempt += 1) {
      await store.refreshConvergence();
      if (store.convergence?.status === 'CONVERGED') {
        ElMessage.success('所有已注册数据面已生效最新配置');
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, 2000));
    }
    ElMessage.warning(`配置已发布，但数据面尚未全部收敛：${store.convergence?.status || 'UNKNOWN'}`);
  } finally {
    waitingConvergence.value = false;
  }
}

async function rollback(version: ConfigVersion) {
  try {
    await ElMessageBox.confirm(
      `将基于版本 ${version.versionId} 生成更大的 routeEpoch，不会降低数据面当前 epoch。`,
      '确认回滚',
      { type: 'warning' }
    );
  } catch {
    return;
  }
  try {
    await api.config.rollback({ versionId: version.versionId, operator: operator.value, reason: reason.value || `rollback-to-${version.versionId}` });
    ElMessage.success('回滚版本已发布');
    await load();
  } catch (error) {
    ElMessage.error(`回滚失败：${errorMessage(error)}`);
  }
}

async function previewDiff() {
  if (!selectedVersion.value || !diffTarget.value) {
    ElMessage.warning('请选择基准版本和目标版本');
    return;
  }
  if (selectedVersion.value === diffTarget.value) {
    ElMessage.warning('基准版本和目标版本相同，无需对比');
    return;
  }
  diff.value = await api.config.diff(selectedVersion.value, diffTarget.value);
  diffDialogVisible.value = true;
}

function useLatestAsTarget() {
  if (!latestVersion.value) return;
  diffTarget.value = latestVersion.value.versionId;
}

function versionOptionLabel(version: ConfigVersion, role: 'from' | 'to') {
  const prefix = role === 'to' && latestVersion.value?.versionId === version.versionId ? '当前版本 ' : '';
  return `${prefix}v${version.versionId} / epoch ${version.routeEpoch}`;
}

function versionRowClass({ row }: { row: ConfigVersion }) {
  if (row.versionId === selectedVersion.value) return 'is-from-version';
  if (row.versionId === diffTarget.value) return 'is-to-version';
  return '';
}

function viewVersionConfig(version: ConfigVersion) {
  viewedVersion.value = version;
  versionConfigViewMode.value = 'json';
  viewedVersionDiffTarget.value = latestVersion.value?.versionId === version.versionId
    ? sortedVersions.value.find((candidate) => candidate.versionId !== version.versionId)?.versionId
    : latestVersion.value?.versionId;
  versionConfigDialogVisible.value = true;
}

async function compareViewedVersion() {
  if (!viewedVersion.value || !viewedVersionDiffTarget.value) {
    ElMessage.warning('请选择要对比的目标版本');
    return;
  }
  selectedVersion.value = viewedVersion.value.versionId;
  diffTarget.value = viewedVersionDiffTarget.value;
  versionConfigDialogVisible.value = false;
  await previewDiff();
}

function mergeConvergenceStatus(left: string, right: string) {
  const weight: Record<string, number> = {
    CONVERGED: 0,
    PARTIAL: 1,
    STALE: 2,
    DRIFT: 3,
    UNREACHABLE: 4
  };
  return (weight[right] ?? 1) > (weight[left] ?? 1) ? right : left;
}

function addCluster() {
  draft.value.backends.clusters.push({
    name: `redis-${draft.value.backends.clusters.length + 1}`,
    nodes: ['127.0.0.1:6379'],
    auth: { enabled: false, username: '', password: '' },
    pool: { connectionsPerNode: 16, maxInflightPerConnection: 4096 }
  });
}

function addRouteRule() {
  draft.value.routing.rules.push({
    name: `rule-${draft.value.routing.rules.length + 1}`,
    cluster: draft.value.routing.defaultCluster || '',
    trafficPercent: 100
  });
}

function newRouteRule(prefix: string, cluster: string): RouteRule {
  return {
    name: `${prefix}-${Date.now().toString(36)}`,
    cluster,
    trafficPercent: 100
  };
}

function addProxyGroup() {
  const enabledClusters = draft.value.backends.clusters.map((cluster) => cluster.name).filter(Boolean);
  const defaultCluster = draft.value.routing.defaultCluster || enabledClusters[0] || '';
  draft.value.proxyGroups.push({
    name: `group-${draft.value.proxyGroups.length + 1}`,
    enabledClusters,
    routing: {
      defaultCluster,
      routeEpoch: draft.value.routing.routeEpoch,
      clusterSlotsRefreshIntervalSeconds: draft.value.routing.clusterSlotsRefreshIntervalSeconds,
      backendAffinityStrategy: draft.value.routing.backendAffinityStrategy || 'client',
      rules: []
    }
  });
}

function addProxyGroupRule(group: ProxyGroup) {
  group.routing ||= { defaultCluster: group.enabledClusters[0] || '', rules: [] };
  group.routing.rules ||= [];
  group.routing.rules.push(newRouteRule(`${group.name || 'group'}-rule`, group.routing.defaultCluster || group.enabledClusters[0] || ''));
}

function addNamespace() {
  draft.value.governance.namespaces.push({
    name: `app-${draft.value.governance.namespaces.length + 1}`,
    token: '',
    readOnly: false,
    allowedKeyPrefixes: [],
    deniedCommands: [],
    warnOnlyCommands: [],
    limits: { maxConnections: 0, maxQps: 0, maxInflight: 0 },
    disabledKeys: [],
    keyRules: []
  });
}

function addKeyRule(namespace: Namespace) {
  namespace.keyRules ||= [];
  namespace.keyRules.push({
    name: `key-rule-${namespace.keyRules.length + 1}`,
    keyPrefix: '',
    disabled: false,
    maxQps: 0
  });
}

function removeAt<T>(items: T[], index: number) {
  items.splice(index, 1);
}

function removeKeyRule(namespace: Namespace, index: number) {
  namespace.keyRules ||= [];
  namespace.keyRules.splice(index, 1);
}

function splitCsv(value?: string[]): string {
  return value?.join(', ') || '';
}

function applyCsv(value: string, update: (items: string[]) => void) {
  update(value.split(',').map((item) => item.trim()).filter(Boolean));
}

onMounted(load);
</script>

<template>
  <div v-loading="store.loading">
    <div class="toolbar">
      <div class="toolbar-left">
        <template v-if="isEditing">
          <el-input v-model="operator" placeholder="operator" style="width: 180px" />
          <el-input v-model="reason" placeholder="reason" style="width: 280px" />
        </template>
        <template v-else>
          <span class="subtle">默认只读查看；需要变更时再进入编辑态。</span>
        </template>
      </div>
      <div class="toolbar-right">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <template v-if="isEditing">
          <el-button @click="cancelEdit">取消编辑</el-button>
          <el-button :icon="RefreshLeft" @click="resetEditor">重置为当前配置</el-button>
          <el-button type="primary" :icon="Check" :loading="publishing || waitingConvergence" @click="openPublishReview">审阅并发布</el-button>
        </template>
        <template v-else>
          <el-button type="primary" @click="startStructuredEdit">进入结构化编辑</el-button>
          <el-button @click="startJsonEdit">高级 JSON 编辑</el-button>
        </template>
      </div>
    </div>

    <el-alert v-if="store.error" :title="store.error" type="error" show-icon class="section" />

    <section class="panel section">
      <div class="panel-header">
        <div class="title-with-help">
          <h2>数据面分组收敛</h2>
          <ConvergenceStatusHelp />
        </div>
        <div class="toolbar-right">
          <StatusTag :label="store.convergence?.status || 'UNKNOWN'" :type="convergenceTone(store.convergence?.status)" />
          <span class="subtle">expected epoch={{ store.convergence?.expectedRouteEpoch || '-' }}</span>
          <span class="subtle">hash={{ compactHash(store.convergence?.expectedConfigHash) }}</span>
        </div>
      </div>
      <div class="panel-body">
        <el-alert
          title="proxy 分组是数据面本地部署身份，不会写入全局发布 YAML；配置发布后通过每台 proxy 的 routeEpoch + configHash 判断是否实际生效。"
          type="info"
          show-icon
          :closable="false"
          class="section"
        />
        <div v-if="convergenceGroups.length" class="group-convergence-grid">
          <div v-for="group in convergenceGroups" :key="group.group" class="group-convergence-card">
            <div class="group-card-header">
              <strong>{{ group.group }}</strong>
              <StatusTag :label="group.status" :type="convergenceTone(group.status)" />
            </div>
            <div class="group-card-metrics">
              <span>total <strong>{{ group.total }}</strong></span>
              <span>ok <strong>{{ group.converged }}</strong></span>
              <span>stale <strong>{{ group.stale }}</strong></span>
              <span>drift <strong>{{ group.drift }}</strong></span>
              <span>down <strong>{{ group.unreachable }}</strong></span>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无已注册 proxy；启用数据面 registration 或在 Proxy 实例页手动注册后参与收敛判断" />
        <el-table v-if="store.convergence?.proxies?.length" :data="store.convergence.proxies" size="small" class="section">
          <el-table-column prop="group" label="group" width="120" />
          <el-table-column prop="proxyId" label="proxyId" min-width="180" show-overflow-tooltip />
          <el-table-column prop="dataplane" label="dataplane" width="100" />
          <el-table-column prop="advertiseIp" label="ip" width="130" />
          <el-table-column prop="advertisePort" label="dataPort" width="96" />
          <el-table-column prop="epoch" label="epoch" width="86" />
          <el-table-column label="hash" width="170"><template #default="{ row }">{{ compactHash(row.configHash) }}</template></el-table-column>
          <el-table-column label="status" width="130">
            <template #default="{ row }"><StatusTag :label="row.status" :type="convergenceTone(row.status)" /></template>
          </el-table-column>
          <el-table-column prop="reason" label="reason" min-width="180" show-overflow-tooltip />
          <el-table-column label="lastPoll" width="176"><template #default="{ row }">{{ formatDateTime(row.lastPollTime) }}</template></el-table-column>
        </el-table>
      </div>
    </section>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="当前配置" name="current">
        <section class="panel">
          <div class="panel-header">
            <div>
              <h2>当前生效配置</h2>
              <span class="subtle">只读展示，token/password 已掩码；控制面存储和发布仍使用 JSON。</span>
            </div>
            <el-segmented v-model="readonlyViewMode" :options="[{ label: 'JSON', value: 'json' }, { label: 'YAML 预览', value: 'yaml' }]" />
          </div>
          <div class="panel-body">
            <pre class="code-block config-readonly-block">{{ readonlyConfigText }}</pre>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane v-if="activeTab === 'visual'" label="结构化编辑" name="visual">
        <section class="config-workbench">
          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>结构化编辑</h2>
                <span class="subtle">适合常规发布、治理和切流配置修改</span>
              </div>
              <el-button @click="yamlDrawerVisible = true">YAML 预览</el-button>
            </div>
            <div class="panel-body config-form-scroll">
              <el-collapse v-model="activeCollapse">
                <el-collapse-item title="基础与路由入口" name="basic">
                  <el-form label-width="170px">
                    <el-form-item label="server.listen"><el-input v-model="draft.server.listen" /></el-form-item>
                    <el-form-item label="admin.listen"><el-input v-model="draft.admin.listen" /></el-form-item>
                    <el-form-item label="mode">
                      <el-select v-model="draft.mode" style="width: 180px">
                        <el-option label="standalone" value="standalone" />
                        <el-option label="cluster" value="cluster" />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="defaultCluster"><el-input v-model="draft.routing.defaultCluster" /></el-form-item>
                    <el-form-item label="routeEpoch"><el-input-number v-model="draft.routing.routeEpoch" :min="0" /></el-form-item>
                    <el-form-item label="backendAffinity">
                      <el-select v-model="draft.routing.backendAffinityStrategy" style="width: 180px">
                        <el-option label="client" value="client" />
                        <el-option label="keySlot" value="keySlot" />
                        <el-option label="hashTag" value="hashTag" />
                      </el-select>
                    </el-form-item>
                  </el-form>
                </el-collapse-item>

                <el-collapse-item title="Redis 集群" name="clusters">
                  <div class="toolbar"><span class="subtle">每个 cluster 对应一组 Redis 节点和连接池参数</span><el-button :icon="Plus" @click="addCluster">新增集群</el-button></div>
                  <div v-for="(cluster, index) in draft.backends.clusters" :key="index" class="nested-card">
                    <div class="nested-card-title">
                      <strong>{{ cluster.name || `cluster-${index + 1}` }}</strong>
                      <el-button size="small" type="danger" :icon="Delete" aria-label="删除集群" title="删除集群" @click="removeAt(draft.backends.clusters, index)" />
                    </div>
                    <el-form label-width="140px">
                      <el-form-item label="name"><el-input v-model="cluster.name" /></el-form-item>
                      <el-form-item label="nodes">
                        <el-input :model-value="splitCsv(cluster.nodes)" @update:model-value="applyCsv($event, (items) => (cluster.nodes = items))" />
                      </el-form-item>
                      <el-form-item label="Redis 认证"><el-switch v-model="cluster.auth!.enabled" /></el-form-item>
                      <el-form-item label="ACL 用户名"><el-input v-model="cluster.auth!.username" placeholder="可为空，空值时使用 AUTH password" /></el-form-item>
                      <el-form-item label="Redis 密码"><el-input v-model="cluster.auth!.password" type="password" show-password placeholder="启用认证时必填" /></el-form-item>
                      <el-form-item label="connections/node"><el-input-number v-model="cluster.pool!.connectionsPerNode" :min="1" /></el-form-item>
                      <el-form-item label="maxInflight"><el-input-number v-model="cluster.pool!.maxInflightPerConnection" :min="1" /></el-form-item>
                    </el-form>
                  </div>
                </el-collapse-item>

                <el-collapse-item title="Proxy 分组路由" name="proxyGroups">
                  <div class="toolbar">
                    <span class="subtle">每个 group 只会收到 enabledClusters 引用的 Redis 集群和本组 routing；未配置 proxyGroups 时保持全局配置兼容。</span>
                    <el-button :icon="Plus" @click="addProxyGroup">新增分组</el-button>
                  </div>
                  <el-empty v-if="!draft.proxyGroups.length" description="未配置 proxyGroups，数据面默认使用全局 routing/backends" />
                  <div v-for="(group, groupIndex) in draft.proxyGroups" :key="groupIndex" class="nested-card">
                    <div class="nested-card-title">
                      <strong>{{ group.name || `group-${groupIndex + 1}` }}</strong>
                      <el-button size="small" type="danger" :icon="Delete" aria-label="删除分组" title="删除分组" @click="removeAt(draft.proxyGroups, groupIndex)" />
                    </div>
                    <el-form label-width="150px">
                      <el-form-item label="group name"><el-input v-model="group.name" /></el-form-item>
                      <el-form-item label="enabledClusters">
                        <el-select v-model="group.enabledClusters" multiple filterable style="width: 100%">
                          <el-option v-for="cluster in draft.backends.clusters" :key="cluster.name" :label="cluster.name" :value="cluster.name" />
                        </el-select>
                      </el-form-item>
                      <el-form-item label="defaultCluster">
                        <el-select v-model="group.routing!.defaultCluster" filterable style="width: 240px">
                          <el-option v-for="cluster in group.enabledClusters" :key="cluster" :label="cluster" :value="cluster" />
                        </el-select>
                      </el-form-item>
                      <el-form-item label="backendAffinity">
                        <el-select v-model="group.routing!.backendAffinityStrategy" style="width: 180px">
                          <el-option label="client" value="client" />
                          <el-option label="keySlot" value="keySlot" />
                          <el-option label="hashTag" value="hashTag" />
                        </el-select>
                      </el-form-item>
                    </el-form>
                    <div class="toolbar">
                      <span class="subtle">组内规则只允许路由到 enabledClusters 中的 cluster</span>
                      <el-button size="small" :icon="Plus" @click="addProxyGroupRule(group)">新增组内规则</el-button>
                    </div>
                    <el-table :data="group.routing?.rules || []" size="small">
                      <el-table-column label="name" width="150"><template #default="{ row }"><el-input v-model="row.name" /></template></el-table-column>
                      <el-table-column label="cluster" width="150">
                        <template #default="{ row }">
                          <el-select v-model="row.cluster" filterable>
                            <el-option v-for="cluster in group.enabledClusters" :key="cluster" :label="cluster" :value="cluster" />
                          </el-select>
                        </template>
                      </el-table-column>
                      <el-table-column label="namespace" width="130"><template #default="{ row }"><el-input v-model="row.namespace" /></template></el-table-column>
                      <el-table-column label="keyPrefix" width="160"><template #default="{ row }"><el-input v-model="row.keyPrefix" /></template></el-table-column>
                      <el-table-column label="keyPattern" width="160"><template #default="{ row }"><el-input v-model="row.keyPattern" /></template></el-table-column>
                      <el-table-column label="hashTag" width="120"><template #default="{ row }"><el-input v-model="row.hashTag" /></template></el-table-column>
                      <el-table-column label="matchAll" width="100"><template #default="{ row }"><el-switch v-model="row.matchAll" /></template></el-table-column>
                      <el-table-column label="%" width="110"><template #default="{ row }"><el-input-number v-model="row.trafficPercent" :min="0" :max="100" /></template></el-table-column>
                      <el-table-column label="操作" width="80"><template #default="{ $index }"><el-button size="small" type="danger" :icon="Delete" aria-label="删除组内规则" title="删除组内规则" @click="removeAt(group.routing!.rules!, $index)" /></template></el-table-column>
                    </el-table>
                  </div>
                </el-collapse-item>

                <el-collapse-item title="路由规则" name="rules">
                  <div class="toolbar"><span class="subtle">按顺序匹配，支持 namespace、keyPrefix、keyPattern、hashTag、matchAll 和百分比灰度</span><el-button :icon="Plus" @click="addRouteRule">新增规则</el-button></div>
                  <el-table :data="draft.routing.rules" size="small">
                    <el-table-column label="name" width="150"><template #default="{ row }"><el-input v-model="row.name" /></template></el-table-column>
                    <el-table-column label="cluster" width="130"><template #default="{ row }"><el-input v-model="row.cluster" /></template></el-table-column>
                    <el-table-column label="namespace" width="130"><template #default="{ row }"><el-input v-model="row.namespace" /></template></el-table-column>
                    <el-table-column label="keyPrefix" width="160"><template #default="{ row }"><el-input v-model="row.keyPrefix" /></template></el-table-column>
                    <el-table-column label="keyPattern" width="160"><template #default="{ row }"><el-input v-model="row.keyPattern" /></template></el-table-column>
                    <el-table-column label="hashTag" width="120"><template #default="{ row }"><el-input v-model="row.hashTag" /></template></el-table-column>
                    <el-table-column label="matchAll" width="100"><template #default="{ row }"><el-switch v-model="row.matchAll" /></template></el-table-column>
                    <el-table-column label="%" width="110"><template #default="{ row }"><el-input-number v-model="row.trafficPercent" :min="0" :max="100" /></template></el-table-column>
                    <el-table-column label="操作" width="80"><template #default="{ $index }"><el-button size="small" type="danger" :icon="Delete" aria-label="删除路由规则" title="删除路由规则" @click="removeAt(draft.routing.rules, $index)" /></template></el-table-column>
                  </el-table>
                </el-collapse-item>

                <el-collapse-item title="Limits 与分析阈值" name="limits">
                  <el-form label-width="210px">
                    <el-form-item label="maxPipelineDepth"><el-input-number v-model="draft.limits.maxPipelineDepth" :min="1" /></el-form-item>
                    <el-form-item label="pipelineFlushBatchSize"><el-input-number v-model="draft.limits.pipelineFlushBatchSize" :min="1" /></el-form-item>
                    <el-form-item label="pipelineFlushMaxDelayMillis"><el-input-number v-model="draft.limits.pipelineFlushMaxDelayMillis" :min="0" /></el-form-item>
                    <el-form-item label="maxRequestBytes"><el-input-number v-model="draft.limits.maxRequestBytes" :min="1" /></el-form-item>
                    <el-form-item label="maxResponseBytes"><el-input-number v-model="draft.limits.maxResponseBytes" :min="1" /></el-form-item>
                    <el-form-item label="largeResponseBytes"><el-input-number v-model="draft.limits.largeResponseBytes" :min="1" /></el-form-item>
                  </el-form>
                </el-collapse-item>

                <el-collapse-item title="治理能力" name="governance">
                  <el-form label-width="170px">
                    <el-form-item label="enabled"><el-switch v-model="draft.governance.enabled" /></el-form-item>
                    <el-form-item label="requireAuth"><el-switch v-model="draft.governance.requireAuth" /></el-form-item>
                    <el-form-item label="deniedCommands">
                      <el-input
                        :model-value="splitCsv(draft.governance.commandPolicy.deniedCommands)"
                        @update:model-value="applyCsv($event, (items) => (draft.governance.commandPolicy.deniedCommands = items))"
                      />
                    </el-form-item>
                    <el-form-item label="warnOnlyCommands">
                      <el-input
                        :model-value="splitCsv(draft.governance.commandPolicy.warnOnlyCommands)"
                        @update:model-value="applyCsv($event, (items) => (draft.governance.commandPolicy.warnOnlyCommands = items))"
                      />
                    </el-form-item>
                  </el-form>
                  <div class="toolbar"><span class="subtle">token 输入后会参与发布，YAML 预览中会掩码展示</span><el-button :icon="Plus" @click="addNamespace">新增 namespace</el-button></div>
                  <div v-for="(namespace, nsIndex) in draft.governance.namespaces" :key="nsIndex" class="nested-card">
                    <div class="nested-card-title">
                      <strong>{{ namespace.name || `namespace-${nsIndex + 1}` }}</strong>
                      <el-button size="small" type="danger" :icon="Delete" aria-label="删除 namespace" title="删除 namespace" @click="removeAt(draft.governance.namespaces, nsIndex)" />
                    </div>
                    <el-form label-width="150px">
                      <el-form-item label="name"><el-input v-model="namespace.name" /></el-form-item>
                      <el-form-item label="token"><el-input v-model="namespace.token" type="password" show-password /></el-form-item>
                      <el-form-item label="readOnly"><el-switch v-model="namespace.readOnly" /></el-form-item>
                      <el-form-item label="allowedPrefixes">
                        <el-input :model-value="splitCsv(namespace.allowedKeyPrefixes)" @update:model-value="applyCsv($event, (items) => (namespace.allowedKeyPrefixes = items))" />
                      </el-form-item>
                      <el-form-item label="disabledKeys">
                        <el-input :model-value="splitCsv(namespace.disabledKeys)" @update:model-value="applyCsv($event, (items) => (namespace.disabledKeys = items))" />
                      </el-form-item>
                      <el-form-item label="limits">
                        <div class="inline-number-group">
                          <span>conn</span><el-input-number v-model="namespace.limits!.maxConnections" :min="0" />
                          <span>qps</span><el-input-number v-model="namespace.limits!.maxQps" :min="0" />
                          <span>inflight</span><el-input-number v-model="namespace.limits!.maxInflight" :min="0" />
                        </div>
                      </el-form-item>
                    </el-form>
                    <div class="toolbar"><span class="subtle">Key Rules</span><el-button size="small" :icon="Plus" @click="addKeyRule(namespace)">新增 key rule</el-button></div>
                    <el-table :data="namespace.keyRules || []" size="small">
                      <el-table-column label="name" width="150"><template #default="{ row }"><el-input v-model="row.name" /></template></el-table-column>
                      <el-table-column label="keyPrefix"><template #default="{ row }"><el-input v-model="row.keyPrefix" /></template></el-table-column>
                      <el-table-column label="hashTag" width="130"><template #default="{ row }"><el-input v-model="row.hashTag" /></template></el-table-column>
                      <el-table-column label="disabled" width="100"><template #default="{ row }"><el-switch v-model="row.disabled" /></template></el-table-column>
                      <el-table-column label="maxQps" width="120"><template #default="{ row }"><el-input-number v-model="row.maxQps" :min="0" /></template></el-table-column>
                      <el-table-column label="操作" width="80"><template #default="{ $index }"><el-button size="small" type="danger" :icon="Delete" aria-label="删除 key rule" title="删除 key rule" @click="removeKeyRule(namespace, $index)" /></template></el-table-column>
                    </el-table>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane v-if="activeTab === 'json'" label="高级 JSON" name="json">
        <section class="panel">
          <div class="panel-header">
            <div>
              <h2>高级 JSON 编辑</h2>
              <span class="subtle">编辑内容来自当前配置 JSON；点击应用到草稿后再发布。</span>
            </div>
            <div>
              <el-button :icon="RefreshLeft" @click="resetEditor">重置为当前配置</el-button>
              <el-button @click="applyJsonToForm">应用到草稿</el-button>
            </div>
          </div>
          <div class="panel-body">
            <textarea v-model="editorText" class="json-editor" spellcheck="false"></textarea>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="版本历史" name="versions">
        <section class="panel">
          <div class="panel-header">
            <div>
              <h2>版本历史</h2>
              <span class="subtle">From 是基准/旧版本，To 是目标/更新后版本</span>
            </div>
          </div>
          <div class="panel-body">
            <div class="version-compare-toolbar">
              <el-select v-model="selectedVersion" placeholder="基准版本 From" style="width: 160px">
                <el-option v-for="v in sortedVersions" :key="v.versionId" :label="versionOptionLabel(v, 'from')" :value="v.versionId" />
              </el-select>
              <el-select v-model="diffTarget" placeholder="目标版本 To" style="width: 190px">
                <el-option v-for="v in sortedVersions" :key="v.versionId" :label="versionOptionLabel(v, 'to')" :value="v.versionId" />
              </el-select>
              <el-button @click="useLatestAsTarget">To = 当前版本</el-button>
              <el-button type="primary" @click="previewDiff">生成对比</el-button>
            </div>
            <el-table :data="sortedVersions" size="small" class="version-table" :row-class-name="versionRowClass">
              <el-table-column prop="versionId" label="version" width="82">
                <template #default="{ row }">
                  <strong>v{{ row.versionId }}</strong>
                </template>
              </el-table-column>
              <el-table-column prop="routeEpoch" label="epoch" width="82" />
              <el-table-column prop="action" label="action" width="108" />
              <el-table-column prop="reason" label="reason" show-overflow-tooltip />
              <el-table-column label="publishedAt" width="168">
                <template #default="{ row }">{{ formatDateTime(row.publishedAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="176" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" @click="viewVersionConfig(row)">查看配置</el-button>
                  <el-button size="small" type="warning" @click="rollback(row)">回滚</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="yamlDrawerVisible" title="YAML 预览" size="60%" append-to-body>
      <div class="yaml-drawer-header">
        <span class="subtle">token/password 已掩码，仅用于发布前确认；实际发布仍使用结构化表单中的真实配置。</span>
      </div>
      <pre class="code-block yaml-preview yaml-preview-drawer">{{ yamlPreview }}</pre>
    </el-drawer>

    <el-dialog v-model="diffDialogVisible" class="diff-dialog" width="88vw" top="5vh" append-to-body>
      <template #header>
        <div class="dialog-title">
          <strong v-if="diff">配置 Diff v{{ diff.fromVersionId }} -> v{{ diff.toVersionId }}</strong>
          <span>From 是基准/旧版本，To 是目标/更新后版本</span>
        </div>
      </template>
      <section v-if="diff && visualDiff" class="diff-workbench diff-dialog-body">
        <div class="diff-content">
          <div class="diff-summary">
            <div>
              <h3>配置变更高亮</h3>
              <p class="subtle">左右两侧展示完整配置；敏感 token/password 已掩码。</p>
            </div>
            <div class="diff-stats">
              <span class="diff-stat add">+{{ visualDiff.stats.added }}</span>
              <span class="diff-stat remove">-{{ visualDiff.stats.removed }}</span>
              <span class="diff-stat same">{{ visualDiff.stats.unchanged }} unchanged</span>
            </div>
          </div>

          <div class="diff-meta-grid">
            <div class="diff-meta-card">
              <span>From 基准/旧版本</span>
              <strong>v{{ selectedFromVersion?.versionId }} / epoch {{ selectedFromVersion?.routeEpoch }}</strong>
              <em>{{ selectedFromVersion?.operator }} · {{ selectedFromVersion?.reason }}</em>
            </div>
            <div class="diff-meta-card">
              <span>To 目标/更新后版本</span>
              <strong>v{{ selectedToVersion?.versionId }} / epoch {{ selectedToVersion?.routeEpoch }}</strong>
              <em>{{ selectedToVersion?.operator }} · {{ selectedToVersion?.reason }}</em>
            </div>
          </div>

          <div v-if="diff.changes.length" class="diff-change-list">
            <span v-for="change in diff.changes" :key="change">{{ change }}</span>
          </div>

          <div class="diff-mode-bar">
            <el-segmented v-model="diffViewMode" :options="[{ label: '左右对比', value: 'side-by-side' }, { label: '统一 Diff', value: 'unified' }]" />
          </div>

          <div v-if="diffViewMode === 'side-by-side'" class="side-by-side-diff">
            <div class="side-diff-header">
              <div>From v{{ selectedFromVersion?.versionId }}</div>
              <div>To v{{ selectedToVersion?.versionId }}</div>
            </div>
            <div class="side-diff-body">
              <div v-for="(row, index) in sideBySideDiff" :key="`side-${index}`" class="side-diff-row" :class="`is-${row.kind}`">
                <div class="side-diff-cell left">
                  <span class="diff-line-no">{{ row.left.line || '' }}</span>
                  <code>{{ row.left.text || ' ' }}</code>
                </div>
                <div class="side-diff-cell right">
                  <span class="diff-line-no">{{ row.right.line || '' }}</span>
                  <code>{{ row.right.text || ' ' }}</code>
                </div>
              </div>
            </div>
          </div>

          <div v-else class="diff-viewer">
            <div
              v-for="(line, index) in visualDiff.lines"
              :key="`${line.kind}-${line.oldLine || 0}-${line.newLine || 0}-${index}`"
              class="diff-line"
              :class="`is-${line.kind}`"
            >
              <span class="diff-line-no">{{ line.oldLine || '' }}</span>
              <span class="diff-line-no">{{ line.newLine || '' }}</span>
              <span class="diff-marker">{{ line.kind === 'add' ? '+' : line.kind === 'remove' ? '-' : ' ' }}</span>
              <code>{{ line.text || ' ' }}</code>
            </div>
          </div>
        </div>
      </section>
    </el-dialog>

    <el-dialog v-model="versionConfigDialogVisible" width="76vw" top="6vh" append-to-body>
      <template #header>
        <div class="dialog-title">
          <strong v-if="viewedVersion">版本配置 v{{ viewedVersion.versionId }} / epoch {{ viewedVersion.routeEpoch }}</strong>
          <span>只读快照，token/password 已掩码；回滚会生成更大的 routeEpoch。</span>
        </div>
      </template>
      <div class="version-config-toolbar">
        <el-segmented v-model="versionConfigViewMode" :options="[{ label: 'JSON', value: 'json' }, { label: 'YAML 预览', value: 'yaml' }]" />
        <div class="version-config-compare">
          <span class="subtle">对比到</span>
          <el-select v-model="viewedVersionDiffTarget" placeholder="选择目标版本" style="width: 210px">
            <el-option
              v-for="version in sortedVersions"
              :key="version.versionId"
              :disabled="version.versionId === viewedVersion?.versionId"
              :label="versionOptionLabel(version, 'to')"
              :value="version.versionId"
            />
          </el-select>
          <el-button type="primary" :disabled="!viewedVersionDiffTarget || viewedVersionDiffTarget === viewedVersion?.versionId" @click="compareViewedVersion">
            对比
          </el-button>
        </div>
      </div>
      <pre class="code-block version-config-viewer">{{ viewedVersionText }}</pre>
    </el-dialog>

    <el-dialog v-model="publishReviewVisible" class="diff-dialog" width="80vw" top="6vh" append-to-body>
      <template #header>
        <div class="dialog-title">
          <strong>审阅并发布配置</strong>
          <span>发布 routeEpoch={{ draft.routing.routeEpoch ?? '-' }}，数据面会按更大 epoch 接受快照；token/password 已掩码。</span>
        </div>
      </template>

      <el-alert
        v-if="publishIssues.length"
        type="error"
        show-icon
        :closable="false"
        :title="`发布前需要修复 ${publishIssues.length} 个问题`"
        class="section"
      >
        <ul class="publish-issue-list">
          <li v-for="(issue, index) in publishIssues" :key="index">{{ issue }}</li>
        </ul>
      </el-alert>
      <el-alert
        v-else-if="!publishHasChanges"
        type="info"
        show-icon
        :closable="false"
        title="草稿与当前生效配置无差异，无需发布"
        class="section"
      />

      <section class="diff-workbench">
        <div class="diff-summary">
          <div>
            <h3>相对当前生效配置的变更</h3>
            <p class="subtle">绿色为新增行，红色为移除行。</p>
          </div>
          <div class="diff-stats">
            <span class="diff-stat add">+{{ publishDiff.stats.added }}</span>
            <span class="diff-stat remove">-{{ publishDiff.stats.removed }}</span>
          </div>
        </div>
        <div class="diff-viewer">
          <div
            v-for="(line, index) in publishDiff.lines"
            :key="`publish-${line.kind}-${line.oldLine || 0}-${line.newLine || 0}-${index}`"
            class="diff-line"
            :class="`is-${line.kind}`"
          >
            <span class="diff-line-no">{{ line.oldLine || '' }}</span>
            <span class="diff-line-no">{{ line.newLine || '' }}</span>
            <span class="diff-marker">{{ line.kind === 'add' ? '+' : line.kind === 'remove' ? '-' : ' ' }}</span>
            <code>{{ line.text || ' ' }}</code>
          </div>
        </div>
      </section>

      <template #footer>
        <el-button @click="publishReviewVisible = false">取消</el-button>
        <el-button
          type="primary"
          :icon="Check"
          :loading="publishing"
          :disabled="publishIssues.length > 0 || !publishHasChanges"
          @click="confirmPublish"
        >
          确认发布
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
