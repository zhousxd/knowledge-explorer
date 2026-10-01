<script setup lang="ts">
import { KeIcon } from '@ke/shared';

/**
 * 403 权限空态(ReviewsView / AssetsView 共用):
 * 锁图标 + 「无访问权限」标题 + 描述 slot(各页自述所需角色)。
 * 后端 403 时整块替换列表主体,避免空表格与分页器残留。
 */
</script>

<template>
  <div
    class="wb-denied"
    data-testid="wb-denied"
  >
    <KeIcon
      name="lock"
      class="denied-icon"
    />
    <p class="denied-title">
      无访问权限
    </p>
    <p class="denied-sub">
      <slot />
    </p>
  </div>
</template>

<style scoped>
.wb-denied { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 48px 0; text-align: center; }
.denied-icon { width: 32px; height: 32px; color: var(--ke-sub); }
.denied-title { margin: 0; color: var(--ke-ink); font-size: 16px; font-weight: 600; }
.denied-sub { margin: 0; color: var(--ke-sub); font-size: 13px; }
</style>
