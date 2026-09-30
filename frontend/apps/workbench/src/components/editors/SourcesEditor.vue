<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import type { SourceRef } from '../../api/types';

/**
 * 来源编辑器(sources 契约,02 §4.3):行增删(title/locator/license/assetId 可空)。
 * content 内 citations[n] 为指向本数组第 n 行的 1-based 索引——顶部恒显「引用索引 1-N」提示。
 * 行内校验与后端同规则(title/locator 非空、assetId 为数字或留空)。
 */
interface SourceRow {
  assetIdRaw: string;
  title: string;
  locator: string;
  license: string;
}

const props = defineProps<{ modelValue: SourceRef[] }>();
const emit = defineEmits<{ (e: 'update:modelValue', value: SourceRef[]): void }>();

const rows = reactive<SourceRow[]>(
  props.modelValue.map((source) => ({
    assetIdRaw: source.assetId == null ? '' : String(source.assetId),
    title: source.title ?? '',
    locator: source.locator ?? '',
    license: source.license ?? ''
  }))
);

function addSource(): void {
  rows.push({ assetIdRaw: '', title: '', locator: '', license: '' });
}
function removeSource(index: number): void {
  rows.splice(index, 1);
}

function rowErrors(index: number): string[] {
  const row = rows[index];
  const list: string[] = [];
  if (!row.title.trim()) {
    list.push(`sources[${index}].title: 请填写题名`);
  }
  if (!row.locator.trim()) {
    list.push(`sources[${index}].locator: 请填写定位(页码/时间码等)`);
  }
  if (row.assetIdRaw !== '' && !/^\d+$/.test(row.assetIdRaw)) {
    list.push(`sources[${index}].assetId: 知识单元 id 须为数字或留空`);
  }
  return list;
}

/** 引用索引提示:第 N 行 → citations 用 N(1-based) */
const indexHint = computed(() =>
  rows.length > 0 ? `引用索引 1-${rows.length}` : '暂无来源,正文不可使用引用'
);

const errors = computed<string[]>(() => rows.flatMap((_, index) => rowErrors(index)));

const payload = computed<SourceRef[]>(() =>
  rows.map((row) => ({
    assetId: row.assetIdRaw === '' ? null : Number(row.assetIdRaw),
    title: row.title,
    locator: row.locator,
    license: row.license === '' ? null : row.license
  }))
);

watch(payload, (value) => emit('update:modelValue', value), { immediate: true });
defineExpose({ errors });
</script>

<template>
  <section class="editor">
    <p class="index-hint">
      {{ indexHint }}
    </p>
    <div
      v-for="(row, index) in rows"
      :key="index"
      class="row-card"
    >
      <div class="row-head">
        <span class="row-no">第 {{ index + 1 }} 条</span>
        <button
          class="del-btn"
          type="button"
          @click="removeSource(index)"
        >
          删除
        </button>
      </div>
      <div class="grid-2">
        <label class="field">
          <span class="field-label">题名</span>
          <input
            v-model="row.title"
            class="input source-title"
            type="text"
            placeholder="《书名》/ 访谈名"
          >
        </label>
        <label class="field">
          <span class="field-label">定位</span>
          <input
            v-model="row.locator"
            class="input source-locator"
            type="text"
            placeholder="第 12 页 / 录音 03:00"
          >
        </label>
        <label class="field">
          <span class="field-label">授权(可空)</span>
          <input
            v-model="row.license"
            class="input source-license"
            type="text"
            placeholder="如:已授权"
          >
        </label>
        <label class="field">
          <span class="field-label">知识单元 id(可空)</span>
          <input
            v-model="row.assetIdRaw"
            class="input source-asset"
            type="text"
            placeholder="挂接知识资源后填写"
          >
        </label>
      </div>
      <p
        v-for="message in rowErrors(index)"
        :key="message"
        class="field-error"
        role="alert"
      >
        {{ message }}
      </p>
    </div>
    <button
      class="add-btn"
      type="button"
      @click="addSource"
    >
      添加来源
    </button>
  </section>
</template>

<style scoped>
.editor { display: flex; flex-direction: column; gap: 10px; }
.index-hint { margin: 0; padding: 7px 12px; border-radius: var(--ke-radius-s); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 12px; }
.row-card { padding: 12px; border: 1px solid var(--ke-line-2); border-radius: var(--ke-radius-s); background: var(--ke-surface-2); display: flex; flex-direction: column; gap: 10px; }
.row-head { display: flex; align-items: center; justify-content: space-between; }
.row-no { color: var(--ke-ink-2); font-size: 12px; font-weight: 600; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.field { display: block; }
.field-label { display: block; margin-bottom: 6px; color: var(--ke-ink-2); font-size: 12px; }
.input { width: 100%; padding: 7px 10px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-s); background: var(--ke-surface); color: var(--ke-ink); font-size: 13px; box-sizing: border-box; }
.input:focus { outline: none; border-color: var(--ke-primary); box-shadow: var(--ke-focus); }
.field-error { margin: 0; color: var(--ke-danger); font-size: 12px; }
.add-btn, .del-btn { padding: 5px 12px; border: 1px dashed var(--ke-line-strong); border-radius: var(--ke-radius-s); background: none; color: var(--ke-sub); font-size: 12px; cursor: pointer; transition: color var(--ke-dur-fast) var(--ke-ease), border-color var(--ke-dur-fast) var(--ke-ease); }
.add-btn { align-self: flex-start; }
.add-btn:hover { border-color: var(--ke-primary); color: var(--ke-primary); }
.del-btn { border-style: solid; border-color: var(--ke-line); }
.del-btn:hover { border-color: var(--ke-danger); color: var(--ke-danger); }
</style>
