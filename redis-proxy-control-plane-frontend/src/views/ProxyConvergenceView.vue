<script setup lang="ts">
import { onMounted } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();

onMounted(() => {
  router.replace({ path: '/routing', query: { tab: 'instances' } });
});
</script>

<template>
  <div class="panel">
    <div class="panel-body subtle">正在跳转到路由与实例收敛...</div>
  </div>
</template>
