<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';

/**
 * 任务卡编辑器(FR-C06):goal、steps(place/observe/minutes>0)增删、
 * recordSchema 标签式增删(内置建议:文本/照片)。
 * 行内校验与后端同规则(goal/observe 非空、minutes 正整数、recordSchema 非空)。
 */
interface StepRow {
  place: string;
  observe: string;
  minutesRaw: string;
}

const props = defineProps<{ modelValue: Record<string, unknown>; sourcesCount: number }>();
const emit = defineEmits<{ (e: 'update:modelValue', value: Record<string, unknown>): void }>();

/** recordSchema 内置建议(02 §4.3 记录项:文本/照片) */
const SCHEMA_SUGGESTIONS = ['文本', '照片'] as const;

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' ? (value as Record<string, unknown>) : {};
}
function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}
function asStringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [];
}

const goal = ref(asString(props.modelValue.goal));
const steps = reactive<StepRow[]>(
  (Array.isArray(props.modelValue.steps) && props.modelValue.steps.length > 0
    ? (props.modelValue.steps as unknown[]).map(asRecord)
    : [{}]
  ).map((step) => ({
    place: asString(step.place),
    observe: asString(step.observe),
    minutesRaw: typeof step.minutes === 'number' && step.minutes > 0 ? String(step.minutes) : '30'
  }))
);
const recordSchema = reactive<string[]>(asStringList(props.modelValue.recordSchema));
/** 记录项输入框草稿值 */
const newSchemaItem = ref('');

function addStep(): void {
  steps.push({ place: '', observe: '', minutesRaw: '30' });
}
function removeStep(index: number): void {
  steps.splice(index, 1);
}
function addSchemaItem(name: string): void {
  if (name.trim() && !recordSchema.includes(name.trim())) {
    recordSchema.push(name.trim());
  }
}
function removeSchemaItem(index: number): void {
  recordSchema.splice(index, 1);
}

function stepErrors(index: number): string[] {
  const row = steps[index];
  const list: string[] = [];
  if (!row.observe.trim()) {
    list.push(`steps[${index}].observe: 请填写观察记录要求`);
  }
  if (!/^\d+$/.test(row.minutesRaw) || Number(row.minutesRaw) < 1) {
    list.push(`steps[${index}].minutes: 时长须为正整数(分钟)`);
  }
  return list;
}

const errors = computed<string[]>(() => {
  const list: string[] = [];
  if (!goal.value.trim()) {
    list.push('goal: 请填写任务目标');
  }
  steps.forEach((_, index) => list.push(...stepErrors(index)));
  if (recordSchema.length === 0) {
    list.push('recordSchema: 至少填写一个记录项');
  }
  return list;
});

const payload = computed<Record<string, unknown>>(() => ({
  goal: goal.value,
  steps: steps.map((row) => ({ place: row.place, observe: row.observe, minutes: Number(row.minutesRaw) })),
  recordSchema: [...recordSchema]
}));

watch(payload, (value) => emit('update:modelValue', value), { immediate: true });
defineExpose({ errors });
</script>

<template>
  <section class="editor">
    <label class="field">
      <span class="field-label">任务目标</span>
      <textarea
        v-model="goal"
        class="input area goal-input"
        rows="2"
        placeholder="本任务卡要完成什么"
      />
    </label>

    <fieldset class="block">
      <legend class="block-title">
        步骤
      </legend>
      <div
        v-for="(row, index) in steps"
        :key="index"
        class="row-card"
      >
        <div class="grid-3">
          <label class="field">
            <span class="field-label">地点(可空)</span>
            <input
              v-model="row.place"
              class="input step-place"
              type="text"
              placeholder="在哪里观察"
            >
          </label>
          <label class="field">
            <span class="field-label">观察记录要求</span>
            <input
              v-model="row.observe"
              class="input step-observe"
              type="text"
              placeholder="观察什么、记什么"
            >
          </label>
          <label class="field">
            <span class="field-label">时长(分钟)</span>
            <input
              v-model="row.minutesRaw"
              class="input step-minutes"
              type="number"
              min="1"
              step="1"
            >
          </label>
        </div>
        <p
          v-for="message in stepErrors(index)"
          :key="message"
          class="field-error"
          role="alert"
        >
          {{ message }}
        </p>
        <button
          class="del-btn"
          type="button"
          @click="removeStep(index)"
        >
          删除步骤
        </button>
      </div>
      <button
        class="add-btn"
        type="button"
        @click="addStep"
      >
        添加步骤
      </button>
    </fieldset>

    <fieldset class="block">
      <legend class="block-title">
        记录项
      </legend>
      <div class="schema-chips">
        <span
          v-for="(item, index) in recordSchema"
          :key="`${item}-${index}`"
          class="schema-chip"
        >
          {{ item }}
          <button
            class="chip-del"
            type="button"
            :aria-label="`删除记录项 ${item}`"
            @click="removeSchemaItem(index)"
          >
            删除
          </button>
        </span>
        <span
          v-if="recordSchema.length === 0"
          class="schema-empty"
        >
          暂无记录项
        </span>
      </div>
      <div class="schema-add">
        <input
          v-model="newSchemaItem"
          class="input schema-input"
          type="text"
          placeholder="自定义记录项"
          @keydown.enter.prevent="addSchemaItem(newSchemaItem); newSchemaItem = ''"
        >
        <button
          class="add-btn"
          type="button"
          @click="addSchemaItem(newSchemaItem); newSchemaItem = ''"
        >
          添加记录项
        </button>
        <button
          v-for="suggestion in SCHEMA_SUGGESTIONS"
          :key="suggestion"
          class="suggest-btn"
          type="button"
          :disabled="recordSchema.includes(suggestion)"
          @click="addSchemaItem(suggestion)"
        >
          {{ suggestion }}
        </button>
      </div>
    </fieldset>
  </section>
</template>

<style scoped>
.editor { display: flex; flex-direction: column; gap: 12px; }
.field { display: block; }
.field-label { display: block; margin-bottom: 6px; color: var(--ke-ink-2); font-size: 12px; }
.input { width: 100%; padding: 7px 10px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-s); background: var(--ke-surface); color: var(--ke-ink); font-size: 13px; box-sizing: border-box; }
.input:focus { outline: none; border-color: var(--ke-primary); box-shadow: var(--ke-focus); }
.area { resize: vertical; }
.block { margin: 0; padding: 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); display: flex; flex-direction: column; gap: 10px; }
.block-title { padding: 0 6px; color: var(--ke-ink); font-size: 13px; font-weight: 600; }
.row-card { padding: 12px; border: 1px solid var(--ke-line-2); border-radius: var(--ke-radius-s); background: var(--ke-surface-2); display: flex; flex-direction: column; gap: 10px; }
.grid-3 { display: grid; grid-template-columns: 1fr 2fr 100px; gap: 10px; }
.field-error { margin: 0; color: var(--ke-danger); font-size: 12px; }
.schema-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.schema-chip { display: inline-flex; align-items: center; gap: 6px; padding: 3px 10px; border-radius: var(--ke-radius-full); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 12px; }
.chip-del { border: none; background: none; color: var(--ke-primary); font-size: 11px; cursor: pointer; text-decoration: underline; }
.chip-del:hover { color: var(--ke-danger); }
.schema-empty { color: var(--ke-sub-2); font-size: 12px; }
.schema-add { display: flex; align-items: center; gap: 8px; }
.schema-input { width: 180px; }
.add-btn, .del-btn, .suggest-btn { padding: 5px 12px; border: 1px dashed var(--ke-line-strong); border-radius: var(--ke-radius-s); background: none; color: var(--ke-sub); font-size: 12px; cursor: pointer; white-space: nowrap; transition: color var(--ke-dur-fast) var(--ke-ease), border-color var(--ke-dur-fast) var(--ke-ease); }
.suggest-btn:disabled { opacity: 0.5; cursor: default; }
.add-btn:hover, .suggest-btn:hover:not(:disabled) { border-color: var(--ke-primary); color: var(--ke-primary); }
.del-btn { border-style: solid; border-color: var(--ke-line); align-self: flex-start; }
.del-btn:hover { border-color: var(--ke-danger); color: var(--ke-danger); }
</style>
