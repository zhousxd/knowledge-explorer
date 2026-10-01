<script setup lang="ts">
// highlightIndex(P3-15 出处联动):点击正文角标后由调用方传入对应 1 基序号,
// 该行高亮 + 短暂放大提示;2s 后调用方置回 null 即回落
defineProps<{
  sources: { index: number; title: string; locator: string; license?: string }[];
  gap?: string;
  highlightIndex?: number | null;
}>();
</script>

<template>
  <div class="src">
    <b>出处清单</b>
    <p
      v-for="s in sources"
      :key="s.index"
      class="row"
      :class="{ 'row-hl': s.index === highlightIndex }"
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
.row { margin: 0; transition: background var(--ke-dur-fast) var(--ke-ease), transform var(--ke-dur-fast) var(--ke-ease); }
.row.row-hl { margin: 0 -4px; padding: 0 4px; border-radius: var(--ke-radius-xs); background: var(--ke-primary-soft); color: var(--ke-ink); transform: scale(1.03); transform-origin: left center; }
.gap { margin: 4px 0 0; color: var(--ke-warn); }
</style>
