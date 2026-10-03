<script setup lang="ts">
import { ThemeArtwork } from '@ke/shared';
import { HOME_THEMES } from '../mock/home';

// 主题专题格(FR-C01):等宽 3 列,宋体专题名 + SVG 线性图标,点击向父级透出专题 key
const emit = defineEmits<{ select: [theme: string] }>();
</script>

<template>
  <div class="themes">
    <button
      v-for="(theme, index) in HOME_THEMES"
      :key="theme.key"
      type="button"
      class="theme"
      :class="`theme-${theme.key}`"
      @click="emit('select', theme.key)"
    >
      <span class="t-index">0{{ index + 1 }} / {{ theme.key === 'academy' ? '地方与人文' : theme.key === 'cuisine' ? '风味与生活' : '日常与科学' }}</span>
      <ThemeArtwork
        class="t-art"
        :theme="theme.key"
      />
      <span class="t-name">
        {{ theme.name }}
      </span>
      <span class="t-count">
        {{ theme.count }} 张卡 · 示例
      </span>
      <span class="t-open">打开专题 ↗</span>
    </button>
  </div>
</template>

<style scoped>
.themes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.theme { position: relative; display: flex; flex-direction: column; align-items: flex-start; min-height: 170px; overflow: hidden; padding: 18px; border: none; border-radius: var(--ke-radius-xs); color: var(--ke-ink); text-align: left; cursor: pointer; isolation: isolate; }
.theme-academy { grid-column: 1 / -1; min-height: 192px; background: var(--ke-academy); }
.theme-cuisine { background: var(--ke-cuisine); }
.theme-sound { background: var(--ke-sound); }
.t-index { position: relative; z-index: 1; font-family: var(--ke-font-mono); font-size: 10px; letter-spacing: .05em; }
.t-art { position: absolute; z-index: -1; right: -22px; bottom: 32px; width: 150px; height: 100px; opacity: .3; transition: transform var(--ke-dur-slow) var(--ke-ease); }
.theme-academy .t-art { right: 4px; bottom: 20px; width: 56%; height: 150px; opacity: 1; }
.theme:hover .t-art { transform: translateY(-4px); }
.t-name { margin-top: 14px; font-family: var(--ke-font-display); font-size: 24px; font-weight: 400; }
.theme-academy .t-name { font-size: 30px; }
.t-count { margin-top: 8px; font-size: 11px; }
.t-open { margin-top: auto; padding-top: 22px; font-size: 11px; font-weight: 600; }
.theme-academy .t-open { margin-top: 20px; padding: 8px 12px; background: var(--ke-primary); color: var(--ke-white); border-radius: var(--ke-radius-xs); }

@media (width <= 374px) {
  .theme { padding: 14px; }
  .t-index { font-size: 9px; }
  .theme-academy .t-art { right: -5px; width: 51%; }
}
</style>
