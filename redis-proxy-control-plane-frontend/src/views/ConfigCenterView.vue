<script setup lang="ts">
import { Check, Delete, Plus, Refresh, RefreshLeft } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, ref } from 'vue';
import { api } from '../api/client';
import type { Cluster, ConfigDiff, ConfigVersion, KeyRule, Namespace, ProxyConfig, RouteRule } from '../api/types';
import StatusTag from '../components/StatusTag.vue';
import { useControlPlaneStore } from '../stores/controlPlane';
import { formatDateTime, maskTokens } from '../utils/status';
import { toYaml } from '../utils/yaml';

type DraftConfig = ProxyConfig & {
  server: { listen?: string };
  admin: { listen?: string };
  backends: { clusters: Cluster[] };
  routing: NonNullable<ProxyConfig['routing']> & { rules: RouteRule[] };
  limits: NonNullable<ProxyConfig['limits']>;
  governance: NonNullable<ProxyConfig['governance']> & {
    commandPolicy: NonNullable<NonNullable<ProxyConfig['governance']>['commandPolicy']>;
    namespaces: Namespace[];
  };
};

const store = useControlPlaneStore();
const activeTab = ref('visual');
const editorText = ref('');
const operator = ref('system');
const reason = ref('');
const selectedVersion = ref<number>();
const diffTarget = ref<number>();
const diff = ref<ConfigDiff>();
const draft = ref<DraftConfig>(normalizeConfig());

const yamlPreview = computed(() => toYaml(maskTokens(draft.value)));
const currentMaskedJson = computed(() => JSON.stringify(maskTokens(store.config || {}), null, 2));

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

async function load() {
  await store.loadConfigDomain();
  resetEditor();
}

function applyJsonToForm() {
  draft.value = normalizeConfig(JSON.parse(editorText.value) as ProxyConfig);
  ElMessage.success('JSON 已应用到结构化表单');
}

async function publish() {
  await ElMessageBox.confirm(
    `即将发布 routeEpoch=${draft.value.routing.routeEpoch ?? '-'}，发布后会等待数据面按更大 epoch 接受快照。`,
    '确认发布配置',
    { type: 'warning' }
  );
  await api.config.publish(clone(draft.value), operator.value, reason.value || 'frontend-publish');
  ElMessage.success('配置已发布');
  await load();
  await store.refreshConvergence();
}

async function rollback(version: ConfigVersion) {
  await ElMessageBox.confirm(
    `将基于版本 ${version.versionId} 生成更大的 routeEpoch，不会降低数据面当前 epoch。`,
    '确认回滚',
    { type: 'warning' }
  );
  await api.config.rollback({ versionId: version.versionId, operator: operator.value, reason: reason.value || `rollback-to-${version.versionId}` });
  ElMessage.success('回滚版本已发布');
  await load();
}

async function previewDiff() {
  if (!selectedVersion.value || !diffTarget.value) return;
  diff.value = await api.config.diff(selectedVersion.value, diffTarget.value);
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
        <el-input v-model="operator" placeholder="operator" style="width: 180px" />
        <el-input v-model="reason" placeholder="reason" style="width: 280px" />
      </div>
      <div class="toolbar-right">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button :icon="RefreshLeft" @click="resetEditor">重置</el-button>
        <el-button type="primary" :icon="Check" @click="publish">发布</el-button>
      </div>
    </div>

    <el-alert v-if="store.error" :title="store.error" type="error" show-icon class="section" />

    <el-tabs v-model="activeTab">
      <el-tab-pane label="结构化编辑 + YAML 预览" name="visual">
        <section class="config-workbench">
          <div class="panel">
            <div class="panel-header">
              <h2>结构化编辑</h2>
              <span class="subtle">适合常规发布、治理和切流配置修改</span>
            </div>
            <div class="panel-body config-form-scroll">
              <el-collapse :model-value="['basic', 'clusters', 'rules', 'limits', 'governance']">
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
                      <el-button size="small" type="danger" :icon="Delete" @click="removeAt(draft.backends.clusters, index)" />
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
                    <el-table-column label="操作" width="80"><template #default="{ $index }"><el-button size="small" type="danger" :icon="Delete" @click="removeAt(draft.routing.rules, $index)" /></template></el-table-column>
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
                      <el-button size="small" type="danger" :icon="Delete" @click="removeAt(draft.governance.namespaces, nsIndex)" />
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
                      <el-table-column label="操作" width="80"><template #default="{ $index }"><el-button size="small" type="danger" :icon="Delete" @click="removeKeyRule(namespace, $index)" /></template></el-table-column>
                    </el-table>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>
          </div>

          <div class="panel dark-panel">
            <div class="panel-header">
              <h2>YAML 预览</h2>
              <span class="subtle">token 已掩码，仅用于发布前确认</span>
            </div>
            <div class="panel-body">
              <pre class="code-block yaml-preview">{{ yamlPreview }}</pre>
            </div>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="高级 JSON" name="json">
        <section class="grid-2">
          <div class="panel">
            <div class="panel-header"><h2>当前配置（token 掩码）</h2></div>
            <div class="panel-body"><pre class="code-block">{{ currentMaskedJson }}</pre></div>
          </div>
          <div class="panel">
            <div class="panel-header">
              <h2>JSON 编辑器</h2>
              <div>
                <el-button :icon="RefreshLeft" @click="resetEditor">重置</el-button>
                <el-button @click="applyJsonToForm">应用到表单</el-button>
              </div>
            </div>
            <div class="panel-body">
              <textarea v-model="editorText" class="json-editor" spellcheck="false"></textarea>
            </div>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="版本历史" name="versions">
        <section class="panel">
          <div class="panel-header">
            <h2>版本历史</h2>
            <div class="toolbar-right">
              <el-select v-model="selectedVersion" placeholder="from" style="width: 120px">
                <el-option v-for="v in store.versions" :key="v.versionId" :label="v.versionId" :value="v.versionId" />
              </el-select>
              <el-select v-model="diffTarget" placeholder="to" style="width: 120px">
                <el-option v-for="v in store.versions" :key="v.versionId" :label="v.versionId" :value="v.versionId" />
              </el-select>
              <el-button @click="previewDiff">Diff</el-button>
            </div>
          </div>
          <div class="panel-body">
            <el-table :data="store.versions" size="small">
              <el-table-column prop="versionId" label="version" width="90" />
              <el-table-column prop="routeEpoch" label="epoch" width="90" />
              <el-table-column prop="action" label="action" width="120" />
              <el-table-column prop="operator" label="operator" width="130" />
              <el-table-column prop="reason" label="reason" show-overflow-tooltip />
              <el-table-column label="approval" width="120">
                <template #default="{ row }"><StatusTag :label="row.approvalStatus" type="success" /></template>
              </el-table-column>
              <el-table-column label="publishedAt" width="190">
                <template #default="{ row }">{{ formatDateTime(row.publishedAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="120">
                <template #default="{ row }"><el-button size="small" type="warning" @click="rollback(row)">回滚</el-button></template>
              </el-table-column>
            </el-table>
            <el-alert v-if="diff" :title="`Diff ${diff.fromVersionId} -> ${diff.toVersionId}`" type="info" class="section" />
            <ul v-if="diff"><li v-for="change in diff.changes" :key="change">{{ change }}</li></ul>
          </div>
        </section>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
