<script setup lang="ts">
defineProps<{
  label?: string;
  type?: 'success' | 'warning' | 'danger' | 'info' | 'primary';
}>();
</script>

<template>
  <span class="status-pill" :class="`is-${type || 'info'}`">
    <span class="status-dot"></span>
    {{ label || '-' }}
  </span>
</template>
