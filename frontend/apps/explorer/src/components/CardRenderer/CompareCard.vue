<script setup lang="ts">
import { CitationTag } from '@ke/shared';
import type { CompareContent } from '../../api/cards';

// 对比卡(04 §7.2 Compare):维度 × 对象表格,表头 primary-soft 底、首列 surface-2 加粗,
// 单元格 12.5/1.55;外层 overflow:auto + max-height 让 wrap 自身成为纵/横滚动容器,
// thead sticky 才相对 wrap 生效(仅 overflow-x 会使纵向吸顶失效),表头吸顶 + 横滚
defineProps<{ content: CompareContent }>();
// open 为分发器统一透传的事件,对比卡自身只 emit cite
const emit = defineEmits<{ cite: [n: number]; open: [cardId: number] }>();
</script>

<template>
  <div class="cmp-wrap">
    <table class="cmp">
      <thead>
        <tr>
          <th
            class="corner"
            aria-label="维度"
          />
          <th
            v-for="o in content.objects"
            :key="o"
            scope="col"
          >
            {{ o }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(dim, ri) in content.dimensions"
          :key="dim"
        >
          <th scope="row">
            {{ dim }}
          </th>
          <td
            v-for="(o, ci) in content.objects"
            :key="o"
          >
            {{ content.cells[ri]?.[ci] }}
          </td>
        </tr>
      </tbody>
    </table>
  </div>
  <p
    v-if="content.citations?.length"
    class="cmp-cites"
  >
    依据
    <CitationTag
      v-for="n in content.citations"
      :key="n"
      :index="n"
      @click="emit('cite', n)"
    />
  </p>
</template>

<style scoped>
.cmp-wrap { max-height: 60vh; margin-top: 4px; overflow: auto; }
.cmp { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.cmp th, .cmp td { border: 1px solid var(--ke-line); padding: 8px 10px; text-align: left; vertical-align: top; line-height: 1.55; }
.cmp thead th { position: sticky; top: 0; z-index: 1; background: var(--ke-primary-soft); color: var(--ke-ink); font-weight: 700; }
.cmp thead th.corner { width: 1%; }
.cmp tbody th { background: var(--ke-surface-2); color: var(--ke-sub); font-weight: 700; white-space: nowrap; }
.cmp td { color: var(--ke-ink-2); }
.cmp-cites { margin: 8px 0 0; font-size: 11px; color: var(--ke-sub); }
</style>
