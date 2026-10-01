<script setup lang="ts">
// 出处浮层(04 §7.2,P3-15):点正文角标后浮出的出处卡片——
// 「[n]」+ 题名(locator 次行,license 小字);surface 白底细线 shadow-2,圆角 8,最大宽 280
defineProps<{ source: { title: string; locator: string; license?: string }; index: number }>();
</script>

<template>
  <div
    class="cite-pop"
    role="status"
  >
    <span class="idx">[{{ index }}]</span>
    <span class="body">
      <b class="t">{{ source.title }}</b>
      <span class="loc">{{ source.locator }}</span>
      <span
        v-if="source.license"
        class="lic"
      >
        {{ source.license }}
      </span>
    </span>
  </div>
</template>

<style scoped>
.cite-pop { display: flex; gap: 6px; max-width: 280px; padding: 8px 10px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); background: var(--ke-surface); box-shadow: var(--ke-shadow-2); }
.idx { flex-shrink: 0; font-size: 11px; font-weight: 700; color: var(--ke-primary); }
.body { min-width: 0; }
.t { display: block; font-size: 12.5px; line-height: 1.5; color: var(--ke-ink); }
.loc { display: block; margin-top: 1px; font-size: 11px; line-height: 1.5; color: var(--ke-sub); }
.lic { display: block; margin-top: 1px; font-size: 10px; line-height: 1.5; color: var(--ke-sub-2); }
</style>
