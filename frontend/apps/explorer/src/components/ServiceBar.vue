<script setup lang="ts">
import { KeIcon } from '@ke/shared';

// 服务栏(04 §7.2 ServiceBar):卡片页底部常驻三键,语义全站固定不得增删换序(FR-S01);
// 三键均由 P5-21 接线为讲解 run 提交(比较/整理的专属模板由 Task 23/25 接管),
// busy=提交链路进行中:三键整体置灰防双击(提交期间不重复建节点/提交 run)
withDefaults(defineProps<{ busy?: boolean }>(), { busy: false });
const emit = defineEmits<{ explain: []; compare: []; organize: [] }>();
</script>

<template>
  <nav
    class="svcbar"
    aria-label="智能体服务"
  >
    <button
      type="button"
      class="svc pri"
      :disabled="busy"
      @click="emit('explain')"
    >
      <KeIcon
        class="svc-ic"
        name="book"
      />
      讲清楚
    </button>
    <button
      type="button"
      class="svc alt"
      :disabled="busy"
      @click="emit('compare')"
    >
      <KeIcon
        class="svc-ic"
        name="scale"
      />
      帮我比较
    </button>
    <button
      type="button"
      class="svc alt"
      :disabled="busy"
      @click="emit('organize')"
    >
      <KeIcon
        class="svc-ic"
        name="layers"
      />
      整理发现
    </button>
  </nav>
</template>

<style scoped>
.svcbar { position: fixed; left: 0; right: 0; bottom: 0; z-index: var(--ke-z-bar); display: flex; gap: 8px; padding: 10px 12px calc(10px + env(safe-area-inset-bottom, 0px)); background: var(--ke-surface); border-top: 1px solid var(--ke-line); }
.svc { display: flex; flex: 1; align-items: center; justify-content: center; gap: 5px; padding: 10px 0; border: none; border-radius: var(--ke-radius-m); font-size: 13px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; transition: transform var(--ke-dur-fast) var(--ke-ease), background var(--ke-dur-fast) var(--ke-ease); }
.svc:active { transform: scale(0.97); }
.svc.pri { background: var(--ke-primary-soft); color: var(--ke-primary); }
.svc.alt { background: var(--ke-surface); border: 1px solid var(--ke-line); color: var(--ke-ink); }
.svc:disabled { opacity: 0.45; cursor: default; transform: none; }
.svc-ic { width: 15px; height: 15px; }
</style>
