<script setup lang="ts">
import type { ResumeSession } from '../api/sessions';

// 继续探索卡(04 §7.2 Resume):深靛实底无渐变、宋体标题、进度摘要、「继续上次探索 →」描边按钮(FR-E01)
defineProps<ResumeSession>();

const emit = defineEmits<{ continue: [] }>();
</script>

<template>
  <section class="resume">
    <p class="eyebrow">
      继续探索 · {{ progress }}
    </p>
    <h2 class="r-title">
      {{ title }}
    </h2>
    <p class="r-progress">
      已探索 {{ nodes }} 个节点 · {{ branches }} 个分支
    </p>
    <button
      class="resume-btn"
      type="button"
      @click="emit('continue')"
    >
      继续上次探索 →
    </button>
  </section>
</template>

<style scoped>
.resume { display: block; width: 100%; margin: 14px 0 4px; padding: 16px; border: none; border-radius: var(--ke-radius-l); background: var(--ke-primary-deep); color: var(--ke-white); text-align: left; box-sizing: border-box; }
.eyebrow { margin: 0; font-size: 11px; letter-spacing: 2px; opacity: .8; }
.r-title { margin: 6px 0 0; font-family: var(--ke-font-display); font-size: 15px; font-weight: 900; line-height: 1.4; color: var(--ke-white); }
.r-progress { margin: 4px 0 0; font-size: 12px; line-height: 1.6; opacity: .85; }
.resume-btn { margin-top: 12px; padding: 7px 14px; border: 1px solid currentcolor; border-radius: var(--ke-radius-m); background: transparent; color: inherit; font-size: 12px; font-weight: 600; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); }
.resume-btn:active { background: var(--ke-primary); }
</style>
