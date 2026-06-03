import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import DashboardView from '../views/DashboardView.vue';
import ConfigCenterView from '../views/ConfigCenterView.vue';
import RoutingView from '../views/RoutingView.vue';
import ClusterSwitchView from '../views/ClusterSwitchView.vue';
import ProxyConvergenceView from '../views/ProxyConvergenceView.vue';
import GovernanceView from '../views/GovernanceView.vue';
import ObservabilityView from '../views/ObservabilityView.vue';

export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', name: 'dashboard', component: DashboardView, meta: { title: '总览' } },
  { path: '/config', name: 'config', component: ConfigCenterView, meta: { title: '配置中心' } },
  { path: '/routing', name: 'routing', component: RoutingView, meta: { title: '路由与集群调度' } },
  { path: '/cluster-switch', name: 'cluster-switch', component: ClusterSwitchView, meta: { title: '整集群切换' } },
  { path: '/proxies', name: 'proxies', component: ProxyConvergenceView, meta: { title: 'Proxy 实例与收敛' } },
  { path: '/governance', name: 'governance', component: GovernanceView, meta: { title: '治理能力' } },
  { path: '/observability', name: 'observability', component: ObservabilityView, meta: { title: '观测分析' } }
];

export const router = createRouter({
  history: createWebHistory(),
  routes
});
