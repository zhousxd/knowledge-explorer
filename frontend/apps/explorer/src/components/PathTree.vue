<script setup lang="ts">
import { computed } from 'vue';
import { formatShortDateTime } from '../format';
import type { PathNode } from '../api/sessions';
import { usePathStore } from '../stores/path';

/**
 * 路径树(04 §7.2 PathNode):按 childrenMap 递归渲染(SFC 以文件名自引用)。
 * 节点行 = dot + 标题 + 副标 + 动作;dot:当前=主色描边 / 分叉点=实心 / 普通=灰;
 * 「分支」微标挂 ≥2 子节点,「当前」chip 挂当前节点;非当前行出「回到此节点」动作。
 * 连线:子层容器 border-left 竖线 + padding-left 16px 缩进(层级缩进,规范 P6 允许从简)。
 */
const props = withDefaults(defineProps<{ parentId?: number | null }>(), { parentId: null });
const emit = defineEmits<{ pick: [node: PathNode] }>();
const store = usePathStore();

const levelNodes = computed<PathNode[]>(() => store.childrenMap.get(props.parentId) ?? []);

/** 分叉点(≥2 子)挂「分支」微标 */
function isFork(n: PathNode): boolean {
  return (store.childrenMap.get(n.nodeId)?.length ?? 0) >= 2;
}

function subOf(n: PathNode): string {
  const time = formatShortDateTime(n.visitedAt);
  return n.isNewKnowledge ? `${time} · 新知识` : time;
}

/** 历史节点点击=回到此节点(当前节点再点无动作);上抛给页面出浮条 */
function pick(n: PathNode): void {
  if (n.nodeId !== store.currentNodeId) emit('pick', n);
}
</script>

<template>
  <ul
    class="level"
    :class="{ root: parentId === null }"
  >
    <li
      v-for="n in levelNodes"
      :key="n.nodeId"
    >
      <button
        type="button"
        class="node"
        :class="{ cur: n.nodeId === store.currentNodeId }"
        @click="pick(n)"
      >
        <span
          class="dot"
          :class="{ solid: isFork(n) }"
        />
        <span class="txt">
          <span class="nt">
            {{ n.cardTitle || n.questionText || '新节点' }}
            <span
              v-if="isFork(n)"
              class="chip fork"
            >
              分支
            </span>
            <span
              v-if="n.nodeId === store.currentNodeId"
              class="chip now"
            >
              当前
            </span>
          </span>
          <span class="ns">{{ subOf(n) }}</span>
        </span>
        <span
          v-if="n.nodeId !== store.currentNodeId"
          class="nx"
        >
          回到此节点 →
        </span>
      </button>
      <PathTree
        v-if="(store.childrenMap.get(n.nodeId)?.length ?? 0) > 0"
        :parent-id="n.nodeId"
        @pick="emit('pick', $event)"
      />
    </li>
  </ul>
</template>

<style scoped>
.level { margin: 0; padding: 0; list-style: none; }

/* 子层:16px 缩进 + 2px 左竖线作父子连线 */
.level:not(.root) { margin: 2px 0 4px 9px; padding-left: 16px; border-left: 2px solid var(--ke-line); }
.node { display: flex; width: 100%; align-items: flex-start; gap: 9px; margin: 6px 0 0; padding: 10px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); background: var(--ke-surface); text-align: left; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); box-sizing: border-box; }
.node:active { background: var(--ke-primary-soft); }

/* 当前节点:浅靛底 + 靛描边(04 §7.2),不可再「回到」 */
.node.cur { border-color: var(--ke-primary); background: var(--ke-primary-soft); cursor: default; }
.dot { width: 9px; height: 9px; margin-top: 6px; border-radius: var(--ke-radius-full); background: var(--ke-line-strong); flex-shrink: 0; box-sizing: border-box; }
.dot.solid { background: var(--ke-primary); }
.node.cur .dot { background: var(--ke-surface); border: 2.5px solid var(--ke-primary); }
.txt { flex: 1; min-width: 0; }
.nt { display: block; font-size: 13.5px; font-weight: 600; line-height: 1.4; color: var(--ke-ink); }
.ns { display: block; margin-top: 2px; font-size: 11px; color: var(--ke-sub); }
.chip { display: inline-block; margin-left: 6px; padding: 0 7px; border-radius: var(--ke-radius-full); font-size: 10px; font-weight: 600; line-height: 1.6; vertical-align: 1px; }
.chip.fork { background: var(--ke-gen-soft); color: var(--ke-gen); }
.chip.now { background: var(--ke-primary); color: var(--ke-white); }
.nx { flex-shrink: 0; padding-top: 2px; font-size: 11px; color: var(--ke-primary); white-space: nowrap; }
</style>
