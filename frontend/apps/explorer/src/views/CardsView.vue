<script setup lang="ts">
import { useRoute } from 'vue-router';

// 卡片浏览页占位:Phase 3 Task 14 替换为真卡片页;/cards?q=&theme= 由首页搜索/专题透传
const route = useRoute();
</script>

<template>
  <div class="page">
    <h1 class="title">
      卡片
    </h1>
    <p class="sub">
      卡片浏览页建设中，由 Phase 3 Task 14 交付
    </p>
    <p
      v-if="route.query.q"
      class="q"
    >
      搜索词「{{ route.query.q }}」
    </p>
    <p
      v-if="route.query.theme"
      class="q"
    >
      专题「{{ route.query.theme }}」
    </p>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; padding: 24px 16px; background: var(--ke-bg); }
.title { margin: 0; font-family: var(--ke-font-display); font-size: 20px; font-weight: 900; line-height: 1.3; color: var(--ke-ink); }
.sub { margin: 8px 0 0; font-size: 12px; color: var(--ke-sub); }
.q { margin: 6px 0 0; font-size: 13px; color: var(--ke-ink-2); }
</style>
