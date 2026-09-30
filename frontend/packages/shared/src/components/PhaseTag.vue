<script setup lang="ts">
const props = defineProps<{ phase: 'P2' | 'P3'; label?: string }>();
const text = () => props.label ?? (props.phase === 'P2' ? '二期' : '三期');
</script>

<template>
  <span class="phasetag">{{ text() }}</span>
</template>

<style scoped>
.phasetag { display: inline-flex; font-size: 11px; padding: 1px 8px; border-radius: var(--ke-radius-full); background: var(--ke-surface-2); color: var(--ke-sub); border: 1px dashed var(--ke-line-strong); }
</style>
