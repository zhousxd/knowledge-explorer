<script setup lang="ts">
import { CitationTag, KeIcon } from '@ke/shared';
import type { TextContent } from '../../api/cards';

// 图文卡正文(04 §7.2):段 14/1.8 两端对齐,段尾 citations 角标;related「相关联」行可跳转
defineProps<{ content: TextContent }>();
const emit = defineEmits<{ cite: [n: number]; open: [cardId: number] }>();
</script>

<template>
  <div class="text-card">
    <section
      v-for="(s, i) in content.sections"
      :key="i"
      class="sec"
    >
      <h3 class="sec-h">
        {{ s.h }}
      </h3>
      <p class="sec-body">
        {{ s.body }}
        <CitationTag
          v-for="n in s.citations ?? []"
          :key="n"
          :index="n"
          @click="emit('cite', n)"
        />
      </p>
    </section>
    <div
      v-if="content.related?.length"
      class="related"
    >
      <b class="rel-title">相关联</b>
      <button
        v-for="r in content.related"
        :key="`${r.cardId}-${r.relation}`"
        type="button"
        class="rel-row"
        @click="emit('open', r.cardId)"
      >
        <span class="rel-why">
          {{ r.why }}
        </span>
        <span class="rel-meta">
          {{ r.relation }}
          <KeIcon
            class="rel-arrow"
            name="chev"
          />
        </span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.sec-h { line-height: 1.5; color: var(--ke-ink); display: flex; align-items: baseline; gap: 12px; margin: 0 0 14px; font-size: 17px; font-weight: 600; }
.sec:first-of-type .sec-h { margin-top: 0; }
.sec-body { margin: 0; color: var(--ke-ink-2); font-size: 15px; line-height: 2; text-align: left; }
.related { border-top: 1px dashed var(--ke-line); margin-top: 28px; padding-top: 20px; border-top-style: solid; }
.rel-title { display: block; margin-bottom: 6px; font-size: 12px; font-weight: 700; color: var(--ke-sub); }
.rel-row { display: flex; width: 100%; align-items: center; gap: 8px; margin: 0 0 6px; text-align: left; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); box-sizing: border-box; padding: 14px 0; border: none; border-bottom: 1px solid var(--ke-line); background: transparent; border-radius: 0; }
.rel-row:active { background: var(--ke-primary-soft); }
.rel-why { flex: 1; min-width: 0; font-size: 12.5px; line-height: 1.6; color: var(--ke-ink-2); }
.rel-meta { display: flex; flex-shrink: 0; align-items: center; gap: 2px; font-size: 11px; font-weight: 600; color: var(--ke-primary); }
.rel-arrow { width: 12px; height: 12px; }
.text-card { counter-reset: section; }
.sec { margin-top: 26px; }
.sec-h::before { counter-increment: section; content: counter(section, decimal-leading-zero); font-family: var(--ke-font-mono); font-size: 11px; font-weight: 400; color: var(--ke-sub); }
</style>
