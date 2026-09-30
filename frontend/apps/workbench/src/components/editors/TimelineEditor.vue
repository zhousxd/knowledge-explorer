<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { citationsError, citationsOf } from './citations';

/**
 * 时间线卡编辑器(FR-C05):events 增删(year/title/body/citations),
 * 行内校验与后端同规则(year/title 非空、citations 1-based 指向 sources)。
 */
interface EventRow {
  year: string;
  title: string;
  body: string;
  citationsRaw: string;
}

const props = defineProps<{ modelValue: Record<string, unknown>; sourcesCount: number }>();
const emit = defineEmits<{ (e: 'update:modelValue', value: Record<string, unknown>): void }>();

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' ? (value as Record<string, unknown>) : {};
}
function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}
function asNumbers(value: unknown): number[] {
  return Array.isArray(value) ? value.filter((n): n is number => typeof n === 'number') : [];
}

const events = reactive<EventRow[]>(
  (Array.isArray(props.modelValue.events) && props.modelValue.events.length > 0
    ? (props.modelValue.events as unknown[]).map(asRecord)
    : [{}]
  ).map((event) => ({
    year: asString(event.year),
    title: asString(event.title),
    body: asString(event.body),
    citationsRaw: asNumbers(event.citations).join(',')
  }))
);

function addEvent(): void {
  events.push({ year: '', title: '', body: '', citationsRaw: '' });
}
function removeEvent(index: number): void {
  events.splice(index, 1);
}

function eventErrors(index: number): string[] {
  const row = events[index];
  const list: string[] = [];
  if (!row.year.trim()) {
    list.push(`events[${index}].year: 请填写年份`);
  }
  if (!row.title.trim()) {
    list.push(`events[${index}].title: 请填写事件标题`);
  }
  const citationError = citationsError(row.citationsRaw, props.sourcesCount);
  if (citationError) {
    list.push(`events[${index}].citations: ${citationError}`);
  }
  return list;
}

const errors = computed<string[]>(() => events.flatMap((_, index) => eventErrors(index)));

const payload = computed<Record<string, unknown>>(() => ({
  events: events.map((row) => {
    const item: Record<string, unknown> = { year: row.year, title: row.title, body: row.body };
    const citations = citationsOf(row.citationsRaw, props.sourcesCount);
    if (citations !== undefined) {
      item.citations = citations;
    }
    return item;
  })
}));

watch(payload, (value) => emit('update:modelValue', value), { immediate: true });
defineExpose({ errors });
</script>

<template>
  <section class="editor">
    <div
      v-for="(row, index) in events"
      :key="index"
      class="row-card"
    >
      <div class="grid-2">
        <label class="field">
          <span class="field-label">年份</span>
          <input
            v-model="row.year"
            class="input event-year"
            type="text"
            :placeholder="`如:公元 ${976 + index} 年`"
          >
        </label>
        <label class="field">
          <span class="field-label">事件标题</span>
          <input
            v-model="row.title"
            class="input event-title"
            type="text"
            placeholder="事件标题"
          >
        </label>
      </div>
      <label class="field">
        <span class="field-label">正文(可空)</span>
        <textarea
          v-model="row.body"
          class="input area event-body"
          rows="2"
          placeholder="事件描述"
        />
      </label>
      <label class="field">
        <span class="field-label">引用索引(逗号分隔,可空)</span>
        <input
          v-model="row.citationsRaw"
          class="input citations-input"
          type="text"
          placeholder="如:1,2"
        >
      </label>
      <p
        v-for="message in eventErrors(index)"
        :key="message"
        class="field-error"
        role="alert"
      >
        {{ message }}
      </p>
      <button
        class="del-btn"
        type="button"
        @click="removeEvent(index)"
      >
        删除事件
      </button>
    </div>
    <button
      class="add-btn"
      type="button"
      @click="addEvent"
    >
      添加事件
    </button>
  </section>
</template>

<style scoped>
.editor { display: flex; flex-direction: column; gap: 10px; }
.row-card { padding: 12px; border: 1px solid var(--ke-line-2); border-radius: var(--ke-radius-s); background: var(--ke-surface-2); display: flex; flex-direction: column; gap: 10px; }
.grid-2 { display: grid; grid-template-columns: 1fr 2fr; gap: 10px; }
.field { display: block; }
.field-label { display: block; margin-bottom: 6px; color: var(--ke-ink-2); font-size: 12px; }
.input { width: 100%; padding: 7px 10px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-s); background: var(--ke-surface); color: var(--ke-ink); font-size: 13px; box-sizing: border-box; }
.input:focus { outline: none; border-color: var(--ke-primary); box-shadow: var(--ke-focus); }
.area { resize: vertical; }
.field-error { margin: 0; color: var(--ke-danger); font-size: 12px; }
.add-btn, .del-btn { align-self: flex-start; padding: 5px 12px; border: 1px dashed var(--ke-line-strong); border-radius: var(--ke-radius-s); background: none; color: var(--ke-sub); font-size: 12px; cursor: pointer; transition: color var(--ke-dur-fast) var(--ke-ease), border-color var(--ke-dur-fast) var(--ke-ease); }
.add-btn:hover { border-color: var(--ke-primary); color: var(--ke-primary); }
.del-btn { border-style: solid; border-color: var(--ke-line); }
.del-btn:hover { border-color: var(--ke-danger); color: var(--ke-danger); }
</style>
