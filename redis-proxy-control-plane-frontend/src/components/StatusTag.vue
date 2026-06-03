<script setup lang="ts">
defineProps<{
  label?: string;
  type?: 'success' | 'warning' | 'danger' | 'info' | 'primary';
}>();
</script>

<template>
  <el-tag :type="type || 'info'" effect="light">{{ label || '-' }}</el-tag>
</template>
