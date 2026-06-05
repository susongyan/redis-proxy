<script setup lang="ts">
import { QuestionFilled } from '@element-plus/icons-vue';

const statuses = [
  {
    name: 'CONVERGED',
    tone: '已收敛',
    description: '所有健康 proxy 的 routeEpoch、configHash 与控制面期望态一致，且最近一次配置应用成功。'
  },
  {
    name: 'PARTIAL',
    tone: '部分收敛',
    description: '只有部分 proxy 已达到期望态，仍有实例未完成应用或尚未采集到最新快照。'
  },
  {
    name: 'STALE',
    tone: '版本落后',
    description: 'proxy 当前 routeEpoch 小于期望 routeEpoch，或最近一次 apply 结果不是 success。'
  },
  {
    name: 'DRIFT',
    tone: '内容漂移',
    description: 'proxy 的 routeEpoch 与期望相同，但 configHash 不一致，说明生效配置内容和控制面期望态不同。'
  },
  {
    name: 'UNREACHABLE',
    tone: '不可达',
    description: 'Collector 无法采集该 proxy，或数据面 heartbeat 已过期。'
  }
];
</script>

<template>
  <el-popover trigger="click" placement="bottom-start" :width="430" popper-class="convergence-help-popper">
    <template #reference>
      <el-button class="status-help-button" :icon="QuestionFilled" circle text aria-label="收敛状态说明" />
    </template>
    <div class="status-help">
      <strong>收敛状态含义</strong>
      <p>控制面发布成功只代表期望态已更新，状态以每台 proxy 上报的 routeEpoch + configHash + apply 结果 + heartbeat 判断。</p>
      <dl>
        <template v-for="item in statuses" :key="item.name">
          <dt :class="`is-${item.name.toLowerCase()}`">{{ item.name }} · {{ item.tone }}</dt>
          <dd>{{ item.description }}</dd>
        </template>
      </dl>
    </div>
  </el-popover>
</template>

<style scoped>
.status-help-button {
  width: 24px;
  height: 24px;
  color: #67e8f9;
}

.status-help {
  color: #dbeafe;
  font-size: 13px;
  line-height: 1.55;
}

.status-help strong {
  display: block;
  margin-bottom: 8px;
  color: #e5eefb;
  font-size: 14px;
}

.status-help p {
  margin: 0 0 10px;
  color: #9fb0c8;
}

.status-help dl {
  margin: 0;
}

.status-help dt {
  margin-top: 9px;
  font-weight: 700;
}

.status-help dt.is-converged {
  color: #86efac;
}

.status-help dt.is-partial,
.status-help dt.is-stale {
  color: #fde68a;
}

.status-help dt.is-drift,
.status-help dt.is-unreachable {
  color: #fca5a5;
}

.status-help dd {
  margin: 2px 0 0;
  color: #cbd5e1;
}
</style>
