<script setup lang="ts">
import type { TaskContent } from '../../api/cards';

// 任务卡站点(04 §7.2 TaskStop):44×44 序号块(主色 soft 底,分钟数)+ 站名 + 观察问题框;
// 卡底部固定声明「实时信息不作确定承诺」(FR-C06)
defineProps<{ content: TaskContent }>();
// 分发器会透传 onCite/onOpen,显式声明避免 fallthrough 警告(任务卡自身不产生这两类事件)
defineEmits<{ cite: [n: number]; open: [cardId: number] }>();
</script>

<template>
  <div class="task-card">
    <p class="goal">
      {{ content.goal }}
    </p>
    <ol class="steps">
      <li
        v-for="(s, i) in content.steps ?? []"
        :key="i"
        class="stop"
      >
        <span class="no">
          {{ s.minutes }}<i>min</i>
        </span>
        <span class="st-body">
          <b class="st-place">
            第 {{ i + 1 }} 站 · {{ s.place || '观察点' }}
          </b>
          <span class="st-observe">
            {{ s.observe }}
          </span>
        </span>
      </li>
    </ol>
    <p
      v-if="content.recordSchema?.length"
      class="schema"
    >
      记录字段:{{ content.recordSchema.join(' · ') }}
    </p>
    <p class="declare">
      实时信息不作确定承诺
    </p>
  </div>
</template>

<style scoped>
.goal { margin: 4px 0 0; font-size: 13.5px; font-weight: 600; line-height: 1.7; color: var(--ke-ink); }
.steps { margin: 10px 0 0; padding: 0; list-style: none; }
.stop { display: flex; gap: 10px; margin: 0 0 10px; }
.no { display: flex; width: 44px; height: 44px; flex-shrink: 0; flex-direction: column; align-items: center; justify-content: center; border-radius: var(--ke-radius-l); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 14px; font-weight: 800; line-height: 1; }
.no i { margin-top: 2px; font-size: 9px; font-style: normal; font-weight: 600; }
.st-body { flex: 1; min-width: 0; }
.st-place { display: block; font-size: 14px; font-weight: 700; line-height: 1.5; color: var(--ke-ink); }
.st-observe { display: block; margin-top: 4px; padding: 7px 10px; border-radius: var(--ke-radius-m); background: var(--ke-surface-2); font-size: 12.5px; line-height: 1.55; color: var(--ke-ink-2); }
.schema { margin: 2px 0 0; font-size: 11px; color: var(--ke-sub); }
.declare { margin: 12px 0 0; padding-top: 9px; border-top: 1px dashed var(--ke-line); font-size: 11px; color: var(--ke-sub-2); }
</style>
