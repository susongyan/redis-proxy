<script setup lang="ts">
import {
  Connection,
  DataAnalysis,
  Files,
  Guide,
  Histogram,
  Operation,
  SetUp,
  Switch
} from '@element-plus/icons-vue';
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const title = computed(() => String(route.meta.title || 'Redis Proxy Control Plane'));

const navItems = [
  { path: '/dashboard', label: '总览', icon: Histogram },
  { path: '/config', label: '配置中心', icon: Files },
  { path: '/routing', label: '路由与集群调度', icon: Guide },
  { path: '/cluster-switch', label: '整集群切换', icon: Switch },
  { path: '/proxies', label: 'Proxy 实例与收敛', icon: Connection },
  { path: '/governance', label: '治理能力', icon: Operation },
  { path: '/observability', label: '观测分析', icon: DataAnalysis },
  { path: '/settings', label: '系统设置', icon: SetUp, disabled: true }
];
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
          <el-tag type="info">API /api/v1</el-tag>
          <el-tag type="success">前后端分离</el-tag>
        </div>
      </el-header>
      <el-main class="page">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>
