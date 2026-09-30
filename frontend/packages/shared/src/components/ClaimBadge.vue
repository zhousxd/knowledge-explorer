<script setup lang="ts">
const props = defineProps<{ type: 'fact' | 'synth' | 'gen' }>();
const CONF = {
  fact: { symbol: '●', label: '事实', aria: '事实陈述，有出处' },
  synth: { symbol: '◐', label: '归纳', aria: '归纳推断，基于出处综合' },
  gen: { symbol: '○', label: '生成', aria: '生成内容，仅供参考' }
} as const;
const conf = CONF[props.type];
</script>

<template>
  <span
    class="claim"
    :class="`c-${type}`"
    :aria-label="conf.aria"
  >
    <span aria-hidden="true">{{ conf.symbol }} {{ conf.label }}</span>
  </span>
</template>

<style scoped>
.claim { display: inline-flex; font-size: 11px; font-weight: 700; padding: 1px 7px; border-radius: var(--ke-radius-xs); border: 1px solid; }
.c-fact { background: var(--ke-fact-soft); color: var(--ke-fact); border-color: rgb(31 122 77 / 28%); }
.c-synth { background: var(--ke-synth-soft); color: var(--ke-synth); border-color: rgb(14 116 144 / 28%); }
.c-gen { background: var(--ke-gen-soft); color: var(--ke-gen); border-color: rgb(168 85 16 / 28%); }
</style>
