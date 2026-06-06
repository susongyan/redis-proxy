<script setup lang="ts">
import {
  DataAnalysis,
  Files,
  Guide,
  Histogram,
  Operation,
  SetUp,
  Switch
} from '@element-plus/icons-vue';
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import StatusTag from './components/StatusTag.vue';
import { useControlPlaneStore } from './stores/controlPlane';
import { convergenceTone } from './utils/status';
import { router } from './router';

const route = useRoute();
const store = useControlPlaneStore();
const title = computed(() => String(route.meta.title || 'Redis Proxy Control Plane'));
const viteEnv = (import.meta as unknown as { env?: Record<string, string> }).env || {};
const appEnv = viteEnv.VITE_APP_ENV || viteEnv.MODE || 'local';
const controlPlaneOk = ref(false);
let topbarTimer: number | undefined;

const navItems = [
  { path: '/dashboard', label: '总览', icon: Histogram },
  { path: '/config', label: '配置中心', icon: Files },
  { path: '/routing', label: '路由与实例收敛', icon: Guide },
  { path: '/cluster-switch', label: '整集群切换', icon: Switch },
  { path: '/governance', label: '治理能力', icon: Operation },
  { path: '/observability', label: '观测分析', icon: DataAnalysis },
  { path: '/settings', label: '系统设置', icon: SetUp, disabled: true }
];

async function refreshTopbarStatus() {
  try {
    await store.refreshConvergence();
    controlPlaneOk.value = true;
  } catch {
    controlPlaneOk.value = false;
  }
}

function goToConvergence() {
  router.push({ path: '/routing', query: { tab: 'instances' } });
}

onMounted(() => {
  refreshTopbarStatus();
  topbarTimer = window.setInterval(() => {
    refreshTopbarStatus();
  }, 5000);
});

onUnmounted(() => {
  if (topbarTimer) {
    window.clearInterval(topbarTimer);
  }
});
</script>

<template>
  <el-container class="app-shell">
    <el-aside class="sidebar" width="236px">
      <div class="brand">
        <div class="brand-mark">RP</div>
        <div>
          <strong>Redis Proxy</strong>
          <span>Control Plane</span>
        </div>
      </div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item
          v-for="item in navItems"
          :key="item.path"
          :index="item.disabled ? '' : item.path"
          :disabled="item.disabled"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div>
          <h1>{{ title }}</h1>
        </div>
        <div class="topbar-actions">
          <span class="ops-pill">ENV {{ appEnv }}</span>
          <StatusTag :label="controlPlaneOk ? 'CP OK' : 'CP ERROR'" :type="controlPlaneOk ? 'success' : 'danger'" />
          <button class="topbar-status-button" type="button" @click="goToConvergence">
            <StatusTag :label="store.convergence?.status || 'UNKNOWN'" :type="convergenceTone(store.convergence?.status)" />
          </button>
        </div>
      </el-header>
      <el-main class="page">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>
