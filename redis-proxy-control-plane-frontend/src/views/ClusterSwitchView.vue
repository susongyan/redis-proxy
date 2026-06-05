<script setup lang="ts">
import { Check, Refresh, Right, Switch, Warning } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { api } from '../api/client';
import type { Cluster } from '../api/types';
import StatusTag from '../components/StatusTag.vue';
import { useControlPlaneStore } from '../stores/controlPlane';
import { canAdvanceClusterSwitch, convergenceTone, formatDateTime, planTone } from '../utils/status';

const store = useControlPlaneStore();
const convergenceRefreshing = ref(false);
let convergenceTimer: number | undefined;
const form = reactive({
  proxyGroup: 'default',
  sourceCluster: '',
  targetCluster: '',
  mode: 'STAGED',
  steps: '0,10,25,50,100',
  operator: 'system',
  reason: 'cluster-switch',
  useNewTargetCluster: false,
  targetClusterDefinition: newClusterDefinition()
});

const activePlan = computed(() => store.plans.find((plan) => !['COMPLETED', 'ROLLED_BACK', 'CANCELLED', 'FAILED'].includes(plan.status)));
const groupOptions = computed(() => store.routeStatus?.groups?.length ? store.routeStatus.groups : [{
  group: 'default',
  expectedVersionId: store.routeStatus?.expectedVersionId || 0,
  expectedRouteEpoch: store.routeStatus?.expectedRouteEpoch || 0,
  expectedConfigHash: store.routeStatus?.expectedConfigHash || '',
  defaultCluster: store.routeStatus?.defaultCluster || store.config?.routing?.defaultCluster || '',
  clusters: store.config?.backends?.clusters?.map((cluster) => cluster.name) || [],
  rules: store.routeStatus?.rules || []
}]);
const selectedGroup = computed(() => groupOptions.value.find((group) => group.group === form.proxyGroup) || groupOptions.value[0]);
const selectedGroupClusterNames = computed(() => selectedGroup.value?.clusters || []);
const targetClusterOptions = computed(() => selectedGroupClusterNames.value.filter((cluster) => cluster !== form.sourceCluster));
const sourceClusterOptions = computed(() => selectedGroup.value?.defaultCluster ? [selectedGroup.value.defaultCluster] : selectedGroupClusterNames.value);
const nextStagePercent = computed(() => {
  const plan = activePlan.value;
  if (!plan || plan.mode !== 'STAGED') return undefined;
  const nextIndex = plan.currentStepIndex + 1;
  return nextIndex >= 0 && nextIndex < plan.steps.length ? plan.steps[nextIndex] : undefined;
});
const startActionLabel = computed(() => {
  const plan = activePlan.value;
  if (!plan) return '启动';
  return plan.mode === 'FULL' ? '启动全量切换' : '启动灰度';
});
const advanceActionLabel = computed(() => nextStagePercent.value == null ? '推进下一阶段' : `推进到 ${nextStagePercent.value}%`);
const canStartActivePlan = computed(() => {
  const plan = activePlan.value;
  return !!plan && ['CREATED', 'PRECHECKED'].includes(plan.status) && store.convergence?.status === 'CONVERGED';
});

async function load() {
  await store.loadClusterSwitch();
  syncFormWithSelectedGroup();
}

async function refreshConvergenceOnly() {
  convergenceRefreshing.value = true;
  try {
    await store.refreshConvergence();
  } finally {
    convergenceRefreshing.value = false;
  }
}

async function createPlan() {
  let steps: number[];
  try {
    steps = form.mode === 'FULL' ? [100] : parseSteps(form.steps);
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : String(error));
    return;
  }
  if (!form.sourceCluster || !form.targetCluster) {
    ElMessage.warning('请选择 sourceCluster 和 targetCluster');
    return;
  }
  if (form.sourceCluster === form.targetCluster) {
    ElMessage.warning('targetCluster 不能与 sourceCluster 相同');
    return;
  }
  const payload: Record<string, unknown> = {
    proxyGroup: form.proxyGroup,
    sourceCluster: form.sourceCluster,
    targetCluster: form.targetCluster,
    mode: form.mode,
    steps,
    operator: form.operator,
    reason: form.reason
  };
  if (form.useNewTargetCluster) {
    payload.targetClusterDefinition = normalizeClusterDefinition(form.targetClusterDefinition);
  }
  await api.clusterSwitch.create(payload);
  ElMessage.success('切换计划已创建');
  await load();
}

async function action(planId: number, label: string, run: () => Promise<unknown>) {
  await ElMessageBox.confirm(`确认执行 ${label}。当前收敛状态：${store.convergence?.status || '-'}`, label, { type: 'warning' });
  await run();
  ElMessage.success(`${label} 已完成`);
  await load();
}

function parseSteps(value: string): number[] {
  const steps = value.split(',').map((item) => Number(item.trim())).filter((item) => !Number.isNaN(item));
  if (!steps.length || steps.some((item) => item < 0 || item > 100)) {
    throw new Error('steps 必须是 0-100 之间的数字列表');
  }
  return [...new Set(steps)].sort((left, right) => left - right);
}

function newClusterDefinition(): Cluster {
  return {
    name: '',
    nodes: ['127.0.0.1:6379'],
    auth: { enabled: false, username: '', password: '' },
    pool: { connectionsPerNode: 16, maxInflightPerConnection: 4096 }
  };
}

function normalizeClusterDefinition(cluster: Cluster): Cluster {
  return {
    ...cluster,
    nodes: cluster.nodes.map((node) => node.trim()).filter(Boolean),
    auth: cluster.auth || { enabled: false, username: '', password: '' },
    pool: cluster.pool || { connectionsPerNode: 16, maxInflightPerConnection: 4096 }
  };
}

function splitCsv(value?: string[]): string {
  return value?.join(', ') || '';
}

function applyCsv(value: string, update: (items: string[]) => void) {
  update(value.split(',').map((item) => item.trim()).filter(Boolean));
}

function syncFormWithSelectedGroup() {
  const group = selectedGroup.value;
  if (!group) return;
  form.proxyGroup = group.group;
  form.sourceCluster = group.defaultCluster || '';
  if (!form.useNewTargetCluster && (!form.targetCluster || form.targetCluster === form.sourceCluster || !selectedGroupClusterNames.value.includes(form.targetCluster))) {
    form.targetCluster = targetClusterOptions.value[0] || '';
  }
}

watch(
  () => form.proxyGroup,
  () => {
    syncFormWithSelectedGroup();
  }
);

watch(
  () => form.useNewTargetCluster,
  (enabled) => {
    if (enabled) {
      form.targetClusterDefinition = newClusterDefinition();
      form.targetClusterDefinition.name = form.targetCluster || '';
    } else {
      form.targetCluster = targetClusterOptions.value[0] || '';
    }
  }
);

watch(
  () => form.targetClusterDefinition.name,
  (name) => {
    if (form.useNewTargetCluster) {
      form.targetCluster = name;
    }
  }
);

watch(
  () => form.mode,
  (mode) => {
    if (mode === 'FULL') {
      form.steps = '100';
    } else if (form.steps === '100') {
      form.steps = '0,10,25,50,100';
    }
  }
);

onMounted(load);
onMounted(() => {
  convergenceTimer = window.setInterval(() => {
    refreshConvergenceOnly().catch(() => undefined);
  }, 3000);
});
onUnmounted(() => {
  if (convergenceTimer) {
    window.clearInterval(convergenceTimer);
  }
});
</script>

<template>
  <div v-loading="store.loading">
    <div class="toolbar">
      <div class="toolbar-left">
        <StatusTag :label="store.convergence?.status" :type="convergenceTone(store.convergence?.status)" />
        <span class="subtle">收敛状态每 3s 自动刷新；未收敛时禁用启动、推进和跳转</span>
      </div>
      <div class="toolbar-right">
        <el-button :icon="Refresh" :loading="convergenceRefreshing" @click="refreshConvergenceOnly">刷新收敛</el-button>
        <el-button :icon="Refresh" @click="load">刷新全部</el-button>
      </div>
    </div>

    <section class="grid-2 section">
      <div class="panel">
        <div class="panel-header"><h2>创建切换计划</h2></div>
        <div class="panel-body">
          <el-form :model="form" label-width="130px">
            <el-form-item label="proxyGroup">
              <el-select v-model="form.proxyGroup" filterable style="width: 100%">
                <el-option v-for="group in groupOptions" :key="group.group" :label="`${group.group} / default=${group.defaultCluster}`" :value="group.group" />
              </el-select>
            </el-form-item>
            <el-form-item label="sourceCluster">
              <el-select v-model="form.sourceCluster" filterable style="width: 100%">
                <el-option v-for="cluster in sourceClusterOptions" :key="cluster" :label="cluster" :value="cluster" />
              </el-select>
              <span class="subtle">必须等于所选 proxyGroup 当前 defaultCluster。</span>
            </el-form-item>
            <el-form-item label="targetCluster">
              <el-select v-if="!form.useNewTargetCluster" v-model="form.targetCluster" filterable style="width: 100%">
                <el-option v-for="cluster in targetClusterOptions" :key="cluster" :label="cluster" :value="cluster" />
              </el-select>
              <el-input v-else v-model="form.targetClusterDefinition.name" placeholder="new target cluster name" />
            </el-form-item>
            <el-form-item label="新增目标集群">
              <el-switch v-model="form.useNewTargetCluster" />
              <span class="subtle">开启后，目标集群会在启动/推进发布时加入该 proxyGroup。</span>
            </el-form-item>
            <template v-if="form.useNewTargetCluster">
              <el-form-item label="nodes">
                <el-input
                  :model-value="splitCsv(form.targetClusterDefinition.nodes)"
                  @update:model-value="applyCsv($event, (items) => (form.targetClusterDefinition.nodes = items))"
                />
              </el-form-item>
              <el-form-item label="Redis 认证"><el-switch v-model="form.targetClusterDefinition.auth!.enabled" /></el-form-item>
              <el-form-item label="ACL 用户名"><el-input v-model="form.targetClusterDefinition.auth!.username" /></el-form-item>
              <el-form-item label="Redis 密码"><el-input v-model="form.targetClusterDefinition.auth!.password" type="password" show-password /></el-form-item>
              <el-form-item label="connections/node"><el-input-number v-model="form.targetClusterDefinition.pool!.connectionsPerNode" :min="1" /></el-form-item>
              <el-form-item label="maxInflight"><el-input-number v-model="form.targetClusterDefinition.pool!.maxInflightPerConnection" :min="1" /></el-form-item>
            </template>
            <el-form-item label="mode">
              <el-segmented v-model="form.mode" :options="['STAGED', 'FULL']" />
            </el-form-item>
            <el-form-item label="steps"><el-input v-model="form.steps" :disabled="form.mode === 'FULL'" /></el-form-item>
            <el-alert
              v-if="form.mode === 'FULL'"
              title="FULL：预检后点击启动会直接发布 100% 切换配置，计划完成后无需推进。"
              type="warning"
              show-icon
              :closable="false"
              class="section"
            />
            <el-alert
              v-else
              title="STAGED：预检后先启动灰度，随后每次等待 CONVERGED 后推进到下一阶段。"
              type="info"
              show-icon
              :closable="false"
              class="section"
            />
            <el-form-item label="operator"><el-input v-model="form.operator" /></el-form-item>
            <el-form-item label="reason"><el-input v-model="form.reason" /></el-form-item>
            <el-alert
              title="创建计划只保存编排状态，不会立即切流；只有启动、推进、跳转或回滚才会发布新配置并等待数据面收敛。"
              type="info"
              show-icon
              :closable="false"
              class="section"
            />
            <el-form-item>
              <el-button type="primary" :icon="Switch" @click="createPlan">创建</el-button>
            </el-form-item>
          </el-form>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header"><h2>当前活跃计划</h2></div>
        <div class="panel-body">
          <el-empty v-if="!activePlan" description="无活跃计划" />
          <template v-else>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="planId">{{ activePlan.planId }}</el-descriptions-item>
              <el-descriptions-item label="status">
                <StatusTag :label="activePlan.status" :type="planTone(activePlan.status)" />
              </el-descriptions-item>
              <el-descriptions-item label="proxyGroup">{{ activePlan.proxyGroup || 'default' }}</el-descriptions-item>
              <el-descriptions-item label="source">{{ activePlan.sourceCluster }}</el-descriptions-item>
              <el-descriptions-item label="target">{{ activePlan.targetCluster }}</el-descriptions-item>
              <el-descriptions-item label="mode">{{ activePlan.mode }}</el-descriptions-item>
              <el-descriptions-item label="current">
                {{ activePlan.currentStepIndex >= 0 ? activePlan.steps[activePlan.currentStepIndex] : '-' }}%
              </el-descriptions-item>
              <el-descriptions-item v-if="activePlan.mode === 'STAGED'" label="next">
                {{ nextStagePercent == null ? '-' : `${nextStagePercent}%` }}
              </el-descriptions-item>
            </el-descriptions>
            <el-alert
              v-if="activePlan.mode === 'FULL'"
              title="FULL 计划：启动会直接发布 100% 切换配置并进入 COMPLETED，不需要推进。"
              type="warning"
              show-icon
              :closable="false"
              class="section"
            />
            <el-alert
              v-else
              title="STAGED 计划：启动发布第一阶段；每次推进前请等待收敛状态变为 CONVERGED。"
              type="info"
              show-icon
              :closable="false"
              class="section"
            />
            <div class="toolbar" style="margin-top: 16px">
              <el-button :icon="Check" @click="action(activePlan.planId, '预检', () => api.clusterSwitch.precheck(activePlan!.planId))">预检</el-button>
              <el-button type="primary" :icon="Right" :disabled="!canStartActivePlan" @click="action(activePlan.planId, startActionLabel, () => api.clusterSwitch.start(activePlan!.planId))">{{ startActionLabel }}</el-button>
              <el-button v-if="activePlan.mode === 'STAGED'" type="primary" :icon="Right" :disabled="!canAdvanceClusterSwitch(activePlan, store.convergence?.status || '')" @click="action(activePlan.planId, advanceActionLabel, () => api.clusterSwitch.advance(activePlan!.planId))">{{ advanceActionLabel }}</el-button>
              <el-button type="warning" :icon="Warning" @click="action(activePlan.planId, '回滚', () => api.clusterSwitch.rollback(activePlan!.planId, form.operator, form.reason || 'rollback'))">回滚</el-button>
            </div>
          </template>
        </div>
      </div>
    </section>

    <section class="panel">
      <div class="panel-header"><h2>切换计划列表</h2></div>
      <div class="panel-body">
        <el-table :data="store.plans" size="small">
          <el-table-column prop="planId" label="ID" width="80" />
          <el-table-column label="group" width="130">
            <template #default="{ row }">{{ row.proxyGroup || 'default' }}</template>
          </el-table-column>
          <el-table-column prop="sourceCluster" label="source" />
          <el-table-column prop="targetCluster" label="target" />
          <el-table-column prop="mode" label="mode" width="100" />
          <el-table-column label="status" width="130">
            <template #default="{ row }"><StatusTag :label="row.status" :type="planTone(row.status)" /></template>
          </el-table-column>
          <el-table-column label="current" width="100">
            <template #default="{ row }">{{ row.currentStepIndex >= 0 ? row.steps[row.currentStepIndex] : '-' }}%</template>
          </el-table-column>
          <el-table-column label="updatedAt" width="190">
            <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </section>
  </div>
</template>
