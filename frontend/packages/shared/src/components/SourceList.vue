<script setup lang="ts">
defineProps<{
  sources: { index: number; title: string; locator: string; license?: string }[];
  gap?: string;
}>();
</script>

<template>
  <div class="src">
    <b>出处清单</b>
    <p
      v-for="s in sources"
      :key="s.index"
      class="row"
    >
      [{{ s.index }}] {{ s.title }} · {{ s.locator }}{{ s.license ? `（${s.license}）` : '' }}
    </p>
    <p
      v-if="gap"
      class="gap"
    >
      ⚠ 证据缺口：{{ gap }}
    </p>
  </div>
</template>

<style scoped>
.src { background: var(--ke-surface-2); border: 1px solid var(--ke-line); border-radius: var(--ke-radius-s); padding: 10px 13px; font-size: 12px; color: var(--ke-sub); line-height: 1.85; }
.src b { color: var(--ke-ink); }
.row { margin: 0; }
.gap { margin: 4px 0 0; color: var(--ke-warn); }
</style>
