import { defineStore } from 'pinia';
import { api } from '../api/client';
import type {
  ClusterSwitchPlan,
  ConfigVersion,
  KeyObservation,
  ObservabilitySummary,
  ProxyConfig,
  RouteConvergence,
  RouteStatus,
  TargetStatus
} from '../api/types';

interface State {
  loading: boolean;
  error: string;
  config?: ProxyConfig;
  versions: ConfigVersion[];
  routeStatus?: RouteStatus;
  convergence?: RouteConvergence;
  targets: TargetStatus[];
  summary?: ObservabilitySummary;
  plans: ClusterSwitchPlan[];
  hotKeys: KeyObservation[];
  largeKeys: KeyObservation[];
  slowQueries: KeyObservation[];
}

export const useControlPlaneStore = defineStore('control-plane', {
  state: (): State => ({
    loading: false,
    error: '',
    versions: [],
    targets: [],
    plans: [],
    hotKeys: [],
    largeKeys: [],
    slowQueries: []
  }),
  actions: {
    async loadOverview() {
      await this.withLoading(async () => {
        const [config, versions, routeStatus, convergence, targets, summary, plans, hotKeys, largeKeys, slowQueries] = await Promise.all([
          api.config.current(),
          api.config.versions(),
          api.routes.status(),
          api.routes.convergence(),
          api.observability.targets(),
          api.observability.summary(),
          api.clusterSwitch.plans(),
          api.observability.hotKeys({ limit: 10 }),
          api.observability.largeKeys({ limit: 10 }),
          api.observability.slowQueries({ limit: 10 })
        ]);
        this.config = config;
        this.versions = versions;
        this.routeStatus = routeStatus;
        this.convergence = convergence;
        this.targets = targets;
        this.summary = summary;
        this.plans = plans;
        this.hotKeys = hotKeys;
        this.largeKeys = largeKeys;
        this.slowQueries = slowQueries;
      });
    },
    async loadConfigDomain() {
      await this.withLoading(async () => {
        const [config, versions, routeStatus, convergence] = await Promise.all([
          api.config.current(),
          api.config.versions(),
          api.routes.status(),
          api.routes.convergence()
        ]);
        this.config = config;
        this.versions = versions;
        this.routeStatus = routeStatus;
        this.convergence = convergence;
      });
    },
    async loadObservability() {
      await this.withLoading(async () => {
        const [summary, targets, hotKeys, largeKeys, slowQueries] = await Promise.all([
          api.observability.summary(),
          api.observability.targets(),
          api.observability.hotKeys({ limit: 100 }),
          api.observability.largeKeys({ limit: 100 }),
          api.observability.slowQueries({ limit: 100 })
        ]);
        this.summary = summary;
        this.targets = targets;
        this.hotKeys = hotKeys;
        this.largeKeys = largeKeys;
        this.slowQueries = slowQueries;
      });
    },
    async loadClusterSwitch() {
      await this.withLoading(async () => {
        const [config, plans, routeStatus, convergence] = await Promise.all([
          api.config.current(),
          api.clusterSwitch.plans(),
          api.routes.status(),
          api.routes.convergence()
        ]);
        this.config = config;
        this.plans = plans;
        this.routeStatus = routeStatus;
        this.convergence = convergence;
      });
    },
    async refreshConvergence() {
      this.convergence = await api.routes.convergence();
    },
    async withLoading(task: () => Promise<void>) {
      this.loading = true;
      this.error = '';
      try {
        await task();
      } catch (error) {
        this.error = error instanceof Error ? error.message : String(error);
      } finally {
        this.loading = false;
      }
    }
  }
});
