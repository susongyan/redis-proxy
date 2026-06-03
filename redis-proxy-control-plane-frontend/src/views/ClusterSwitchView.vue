<script setup lang="ts">
import { Check, Refresh, Right, Switch, Warning } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, reactive } from 'vue';
import { api } from '../api/client';
import StatusTag from '../components/StatusTag.vue';
import { useControlPlaneStore } from '../stores/controlPlane';
import { canAdvanceClusterSwitch, convergenceTone, formatDateTime, planTone } from '../utils/status';

const store = useControlPlaneStore();
const form = reactive({
  sourceCluster: '',
  targetCluster: '',
  mode: 'STAGED',
  steps: '0,10,25,50,100',
  operator: 'system',
  reason: 'cluster-switch'
});

const activePlan = computed(() => store.plans.find((plan) => !['COMPLETED', 'ROLLED_BACK', 'CANCELLED', 'FAILED'].includes(plan.status)));

async function load() {
  await store.loadClusterSwitch();
}

async function createPlan() {
  const steps = form.steps.split(',').map((value) => Number(value.trim())).filter((value) => !Number.isNaN(value));
  await api.clusterSwitch.create({ ...form, steps });
  ElMessage.success('切换计划已创建');
  await load();
}

async function action(planId: number, label: string, run: () => Promise<unknown>) {
  await ElMessageBox.confirm(`确认执行 ${label}。当前收敛状态：${store.convergence?.status || '-'}`, label, { type: 'warning' });
  await run();
  ElMessage.success(`${label} 已完成`);
  await load();
}

onMounted(load);
</script>

<template>
  <div v-loading="store.loading">
    <div class="toolbar">
      <div class="toolbar-left">
        <StatusTag :label="store.convergence?.status" :type="convergenceTone(store.convergence?.status)" />
        <span class="subtle">未收敛时禁用推进和跳转</span>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <section class="grid-2 section">
      <div class="panel">
        <div class="panel-header"><h2>创建切换计划</h2></div>
        <div class="panel-body">
          <el-form :model="form" label-width="130px">
            <el-form-item label="sourceCluster"><el-input v-model="form.sourceCluster" /></el-form-item>
            <el-form-item label="targetCluster"><el-input v-model="form.targetCluster" /></el-form-item>
            <el-form-item label="mode">
              <el-segmented v-model="form.mode" :options="['STAGED', 'FULL']" />
            </el-form-item>
            <el-form-item label="steps"><el-input v-model="form.steps" /></el-form-item>
            <el-form-item label="operator"><el-input v-model="form.operator" /></el-form-item>
            <el-form-item label="reason"><el-input v-model="form.reason" /></el-form-item>
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
              <el-descriptions-item label="source">{{ activePlan.sourceCluster }}</el-descriptions-item>
              <el-descriptions-item label="target">{{ activePlan.targetCluster }}</el-descriptions-item>
              <el-descriptions-item label="mode">{{ activePlan.mode }}</el-descriptions-item>
              <el-descriptions-item label="current">
                {{ activePlan.currentStepIndex >= 0 ? activePlan.steps[activePlan.currentStepIndex] : '-' }}%
              </el-descriptions-item>
            </el-descriptions>
            <div class="toolbar" style="margin-top: 16px">
              <el-button :icon="Check" @click="action(activePlan.planId, '预检', () => api.clusterSwitch.precheck(activePlan!.planId))">预检</el-button>
              <el-button type="primary" :icon="Right" :disabled="store.convergence?.status !== 'CONVERGED'" @click="action(activePlan.planId, '启动', () => api.clusterSwitch.start(activePlan!.planId))">启动</el-button>
              <el-button type="primary" :icon="Right" :disabled="!canAdvanceClusterSwitch(activePlan, store.convergence?.status || '')" @click="action(activePlan.planId, '推进下一阶段', () => api.clusterSwitch.advance(activePlan!.planId))">推进</el-button>
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
