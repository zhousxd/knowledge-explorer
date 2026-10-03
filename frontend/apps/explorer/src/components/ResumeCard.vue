<script setup lang="ts">
import { computed } from 'vue';
import type { ResumeSession } from '../api/sessions';
import { formatRelativeDay } from '../format';

/**
 * 继续探索卡(04 §7.2 Resume):深靛实底无渐变、宋体标题、进度摘要、「继续上次探索 →」
 * 描边按钮(FR-E01)。P4-17 起直用 P4-16 冻结契约 ResumeSession,
 * lastVisitedAt 由前端格式化为「今天/昨天/N月N日探索」。
 */
const props = defineProps<{ session: ResumeSession }>();
const emit = defineEmits<{ continue: [] }>();

const when = computed(() => `${formatRelativeDay(props.session.lastVisitedAt)}探索`);
</script>

<template>
  <section class="resume">
    <p class="eyebrow">
      继续探索 · {{ when }}
    </p>
    <h2 class="r-title">
      {{ session.title }}
    </h2>
    <p class="r-progress">
      {{ session.nodeCount }} 个节点 · {{ session.branchCount }} 个分支
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
.resume { display: grid; grid-template-columns: 1fr auto; gap: 6px 16px; margin: 24px 0 0; padding: 18px 0; border-top: 1px solid var(--ke-line); border-bottom: 1px solid var(--ke-line); color: var(--ke-ink); }
.eyebrow { grid-column: 1 / -1; margin: 0; font-size: 10px; letter-spacing: .06em; color: var(--ke-sub); }
.r-title { margin: 3px 0 0; font-family: var(--ke-font-display); font-size: 20px; font-weight: 400; line-height: 1.5; }
.r-progress { grid-column: 1; margin: 2px 0 0; font-size: 11px; line-height: 1.6; color: var(--ke-sub); }
.resume-btn { grid-column: 2; grid-row: 2 / 4; align-self: center; max-width: 95px; min-height: 44px; padding: 8px 10px; border: none; border-radius: var(--ke-radius-xs); background: var(--ke-accent); color: var(--ke-on-accent); font-size: 11px; line-height: 1.7; cursor: pointer; }
</style>
