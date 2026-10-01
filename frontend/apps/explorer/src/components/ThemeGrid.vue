<script setup lang="ts">
import { KeIcon } from '@ke/shared';
import { HOME_THEMES } from '../mock/home';

// 主题专题格(FR-C01):等宽 3 列,宋体专题名 + SVG 线性图标,点击向父级透出专题 key
const emit = defineEmits<{ select: [theme: string] }>();
</script>

<template>
  <div class="themes">
    <button
      v-for="theme in HOME_THEMES"
      :key="theme.key"
      type="button"
      class="theme"
      @click="emit('select', theme.key)"
    >
      <span class="t-icon">
        <KeIcon :name="theme.icon" />
      </span>
      <span class="t-name">
        {{ theme.name }}
      </span>
      <span class="t-count">
        {{ theme.count }} 张卡
      </span>
    </button>
  </div>
</template>

<style scoped>
.themes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.theme { display: flex; flex-direction: column; align-items: center; padding: 14px 8px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); }
.theme:active { background: var(--ke-primary-soft); }
.t-icon { display: flex; width: 38px; height: 38px; align-items: center; justify-content: center; border-radius: var(--ke-radius-s); background: var(--ke-primary-soft); color: var(--ke-primary); }
.t-name { margin-top: 6px; font-family: var(--ke-font-display); font-size: 13px; font-weight: 700; color: var(--ke-ink); }
.t-count { margin-top: 2px; font-size: 11px; color: var(--ke-sub); }
</style>
