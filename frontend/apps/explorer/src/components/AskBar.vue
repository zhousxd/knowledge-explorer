<script setup lang="ts">
/**
 * 追问条(Task 22 / FR-E08):讲解结果页底部常驻。44 高输入框、聚焦主色描边(04 §7.2);
 * disabled=提交上下文丢失(追问 payload 经路由 history state 继承,刷新即失)——置灰并
 * 由 placeholder 说明原因(§8.7 禁用带原因);回车/点发送 emit('send', 去空文本),
 * busy 或空文本时发送键置灰防重复提交。
 */
const props = withDefaults(
  defineProps<{
    modelValue: string;
    disabled?: boolean;
    busy?: boolean;
    placeholder?: string;
  }>(),
  { disabled: false, busy: false, placeholder: '针对这次讲解,继续问一句…' }
);
const emit = defineEmits<{ 'update:modelValue': [string]; send: [string] }>();

function onInput(e: Event): void {
  emit('update:modelValue', (e.target as HTMLInputElement).value);
}

function trySend(): void {
  const text = props.modelValue.trim();
  if (props.disabled || props.busy || !text) return;
  emit('send', text);
}

/** 回车即发;输入法组合中(isComposing)不触发,避免选词误发 */
function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter' && !e.isComposing) trySend();
}
</script>

<template>
  <nav
    class="askbar"
    aria-label="继续追问"
  >
    <input
      class="ask-in"
      :value="modelValue"
      type="text"
      maxlength="200"
      :placeholder="placeholder"
      :disabled="disabled"
      @input="onInput"
      @keydown="onKeydown"
    >
    <button
      type="button"
      class="ask-send"
      :disabled="disabled || busy || !modelValue.trim()"
      @click="trySend"
    >
      发送
    </button>
  </nav>
</template>

<style scoped>
.askbar { position: fixed; left: 0; right: 0; bottom: 0; z-index: var(--ke-z-bar); display: flex; gap: 8px; background: var(--ke-surface); border-top: 1px solid var(--ke-line); padding: 12px 16px calc(12px + env(safe-area-inset-bottom, 0px)); }
.ask-in { flex: 1; min-width: 0; height: 44px; padding: 0 13px; border: 1px solid var(--ke-line-strong); background: var(--ke-surface); font-size: 14px; font-family: var(--ke-font); color: var(--ke-ink); box-sizing: border-box; min-height: 48px; border-radius: var(--ke-radius-xs); }
.ask-in::placeholder { color: var(--ke-sub-2); }
.ask-in:focus { outline: none; border-color: var(--ke-primary); box-shadow: var(--ke-focus); }
.ask-in:disabled { opacity: 0.55; cursor: default; }
.ask-send { height: 44px; padding: 0 18px; border: none; font-size: 14px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; min-height: 48px; background: var(--ke-primary); color: var(--ke-white); border-radius: var(--ke-radius-xs); }
.ask-send:disabled { opacity: 0.45; cursor: default; }
</style>
