<script setup lang="ts">
import { CitationTag } from '@ke/shared';
import type { TimelineContent } from '../../api/cards';

// 时间线卡(04 §7.2 Timeline):左侧 2px 竖线 + 9px 圆点,首事件 hl 实心;
// 年份 11.5/800 主色;带 cardId 的事件可点跳转对应卡
defineProps<{ content: TimelineContent }>();
const emit = defineEmits<{ cite: [n: number]; open: [cardId: number] }>();
</script>

<template>
  <ol class="tl">
    <li
      v-for="(e, i) in content.events"
      :key="i"
      class="tl-item"
      :class="{ hl: i === 0, link: !!e.cardId }"
      :role="e.cardId ? 'button' : undefined"
      :tabindex="e.cardId ? 0 : undefined"
      @click="e.cardId !== undefined && emit('open', e.cardId)"
      @keydown.enter="e.cardId !== undefined && emit('open', e.cardId)"
    >
      <span class="tl-y">
        {{ e.year }}
      </span>
      <span class="tl-t">
        {{ e.title }}
        <span
          v-if="e.cardId"
          class="tl-go"
        >
          查看该卡
        </span>
      </span>
      <p
        v-if="e.body"
        class="tl-b"
      >
        {{ e.body }}
        <span
          v-if="e.citations?.length"
          class="tl-cites"
          @click.stop
        >
          <CitationTag
            v-for="n in e.citations"
            :key="n"
            :index="n"
            @click="emit('cite', n)"
          />
        </span>
      </p>
      <span
        v-else-if="e.citations?.length"
        class="tl-cites"
        @click.stop
      >
        <CitationTag
          v-for="n in e.citations"
          :key="n"
          :index="n"
          @click="emit('cite', n)"
        />
      </span>
    </li>
  </ol>
</template>

<style scoped>
.tl { margin: 12px 0 0; padding: 0 0 0 16px; border-left: 2px solid var(--ke-line-strong); list-style: none; }
.tl-item { position: relative; padding: 0 0 16px; }
.tl-item:last-child { padding-bottom: 2px; }
.tl-item::before { content: ""; position: absolute; left: -21.5px; top: 4px; width: 9px; height: 9px; border-radius: 50%; background: var(--ke-surface); border: 2px solid var(--ke-primary); box-sizing: border-box; }
.tl-item.hl::before { background: var(--ke-primary); }
.tl-y { display: block; font-size: 11.5px; font-weight: 800; color: var(--ke-primary); }
.tl-t { display: block; margin-top: 2px; font-size: 14.5px; font-weight: 700; line-height: 1.5; color: var(--ke-ink); }
.tl-item.link { cursor: pointer; }
.tl-item.link:active .tl-t { color: var(--ke-primary); }
.tl-go { margin-left: 6px; font-size: 11px; font-weight: 600; color: var(--ke-primary); }
.tl-b { margin: 3px 0 0; font-size: 12.5px; line-height: 1.6; color: var(--ke-sub); }
.tl-cites { display: inline-flex; gap: 3px; margin-top: 3px; }
</style>
