<script setup lang="ts">
import { useRouter } from 'vue-router';
import { KeIcon } from '@ke/shared';

/**
 * 二级页顶栏(04 §7.2 TopBar):返回 + 面包屑(当前节点加粗)+ 指南针路径按钮;
 * 路径入口全站第二页起必现(P3),图标统一指南针(§6 路径心智锚点)。
 * pathActive=本页即路径(如 /path):按钮主色高亮且点击不再跳转。
 */
const props = withDefaults(
  defineProps<{ section?: string; current?: string; pathActive?: boolean }>(),
  { section: '', current: '', pathActive: false }
);
const router = useRouter();

/** 返回:有历史则 back,直达链接兜底回首页 */
function goBack(): void {
  if (window.history.state?.back == null) {
    void router.replace('/home');
  } else {
    router.back();
  }
}

function goPath(): void {
  if (props.pathActive) return;
  void router.push('/path');
}
</script>

<template>
  <header class="topbar">
    <button
      type="button"
      class="ic"
      aria-label="返回"
      @click="goBack"
    >
      <KeIcon name="back" />
    </button>
    <div class="crumb">
      <template v-if="current">
        {{ section }} · <b>{{ current }}</b>
      </template>
      <template v-else>
        <b>{{ section }}</b>
      </template>
    </div>
    <button
      type="button"
      class="ic"
      :class="{ on: pathActive }"
      aria-label="我的路径"
      @click="goPath"
    >
      <KeIcon name="compass" />
    </button>
  </header>
</template>

<style scoped>
.topbar { position: fixed; top: 0; left: 0; right: 0; z-index: var(--ke-z-bar); display: flex; align-items: center; gap: 8px; height: 48px; padding: 0 10px; background: color-mix(in srgb, var(--ke-bg) 86%, transparent); backdrop-filter: blur(8px); border-bottom: 1px solid var(--ke-line-2); }
.ic { display: flex; width: 34px; height: 34px; align-items: center; justify-content: center; border: none; border-radius: var(--ke-radius-full); background: transparent; color: var(--ke-ink); cursor: pointer; }
.ic:active { background: var(--ke-primary-soft); }
.ic.on { color: var(--ke-primary); }
.crumb { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--ke-sub); }
.crumb b { color: var(--ke-ink); font-weight: 700; }
</style>
