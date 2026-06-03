<script setup lang="ts">
defineProps<{
  items: Array<{ label: string; value: string | number; tone?: 'default' | 'good' | 'warn' | 'bad' }>;
}>();
</script>

<template>
  <div class="metric-strip">
    <div v-for="item in items" :key="item.label" class="metric-tile" :class="item.tone">
      <span>{{ item.label }}</span>
      <strong>{{ item.value }}</strong>
    </div>
  </div>
</template>
