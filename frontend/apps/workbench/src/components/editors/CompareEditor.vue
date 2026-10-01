<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { citationsError, citationsOf } from './citations';

/**
 * 对比卡编辑器(FR-C04):objects[] × dimensions[] → cells[][] 网格。
 * 行 = 维度、列 = 对象;增删行/列时联动收缩扩展 cells 并保留既有数据,
 * 保证载荷始终满足后端形状校验(cells 行数=维度数、行内列数=对象数)。
 */
const props = defineProps<{ modelValue: Record<string, unknown>; sourcesCount: number }>();
const emit = defineEmits<{ (e: 'update:modelValue', value: Record<string, unknown>): void }>();

function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function asStringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [];
}

/** 由 content 还原 cells 矩阵并按 objects/dimensions 数量补齐形状(缺失格填空串) */
function initCells(content: Record<string, unknown>): string[][] {
  const dimCount = asStringList(content.dimensions).length;
  const objCount = asStringList(content.objects).length;
  const raw = Array.isArray(content.cells) ? content.cells : [];
  const grid: string[][] = [];
  for (let r = 0; r < dimCount; r += 1) {
    const rowRaw = Array.isArray(raw[r]) ? (raw[r] as unknown[]) : [];
    const row: string[] = [];
    for (let c = 0; c < objCount; c += 1) {
      row.push(asString(rowRaw[c]));
    }
    grid.push(row);
  }
  return grid;
}

const objects = reactive<string[]>(asStringList(props.modelValue.objects));
const dimensions = reactive<string[]>(asStringList(props.modelValue.dimensions));
const cells = reactive<string[][]>(initCells(props.modelValue));
const citationsRaw = ref(asNumbers(props.modelValue.citations).join(','));

function asNumbers(value: unknown): number[] {
  return Array.isArray(value) ? value.filter((n): n is number => typeof n === 'number') : [];
}

function addObject(): void {
  objects.push('');
  cells.forEach((row) => row.push(''));
}
function removeObject(index: number): void {
  objects.splice(index, 1);
  cells.forEach((row) => row.splice(index, 1));
}
function addDimension(): void {
  dimensions.push('');
  cells.push(objects.map(() => ''));
}
function removeDimension(index: number): void {
  dimensions.splice(index, 1);
  cells.splice(index, 1);
}

/** 行名空串/纯空白判定(行内红字 + 保存门禁共用) */
function isBlank(value: string | undefined): boolean {
  return (value ?? '').trim().length === 0;
}

const errors = computed<string[]>(() => {
  const list: string[] = [];
  if (objects.length === 0) {
    list.push('objects: 至少填写一个对比对象');
  }
  if (dimensions.length === 0) {
    list.push('dimensions: 至少填写一个对比维度');
  }
  if (objects.some((name) => isBlank(name))) {
    list.push('objects: 对象名称不能为空');
  }
  if (dimensions.some((name) => isBlank(name))) {
    list.push('dimensions: 维度名称不能为空');
  }
  const citationError = citationsError(citationsRaw.value, props.sourcesCount);
  if (citationError) {
    list.push(`citations: ${citationError}`);
  }
  return list;
});

const payload = computed<Record<string, unknown>>(() => {
  const base: Record<string, unknown> = {
    objects: [...objects],
    dimensions: [...dimensions],
    cells: cells.map((row) => [...row])
  };
  const citations = citationsOf(citationsRaw.value, props.sourcesCount);
  if (citations !== undefined) {
    base.citations = citations;
  }
  return base;
});

watch(payload, (value) => emit('update:modelValue', value), { immediate: true });
defineExpose({ errors });
</script>

<template>
  <section class="editor">
    <div class="grid-wrap">
      <div class="grid-row grid-head">
        <span class="grid-corner">
          维度 \ 对象
        </span>
        <div
          v-for="(name, objectIndex) in objects"
          :key="`obj-${objectIndex}`"
          class="head-cell"
        >
          <input
            v-model="objects[objectIndex]"
            class="input obj-input"
            type="text"
            :placeholder="`对象 ${objectIndex + 1}`"
            :aria-label="`对象 ${objectIndex + 1} 名称`"
          >
          <p
            v-if="isBlank(objects[objectIndex])"
            class="name-error"
            role="alert"
          >
            名称不能为空
          </p>
          <button
            class="del-btn obj-del"
            type="button"
            :aria-label="`删除对象 ${objectIndex + 1}`"
            @click="removeObject(objectIndex)"
          >
            删除
          </button>
        </div>
        <button
          class="add-btn add-object"
          type="button"
          @click="addObject"
        >
          添加对象
        </button>
      </div>
      <div
        v-for="(_, dimensionIndex) in dimensions"
        :key="`dim-${dimensionIndex}`"
        class="grid-row dim-row"
      >
        <div class="dim-cell">
          <div class="dim-field">
            <input
              v-model="dimensions[dimensionIndex]"
              class="input dim-input"
              type="text"
              :placeholder="`维度 ${dimensionIndex + 1}`"
              :aria-label="`维度 ${dimensionIndex + 1} 名称`"
            >
            <p
              v-if="isBlank(dimensions[dimensionIndex])"
              class="name-error"
              role="alert"
            >
              名称不能为空
            </p>
          </div>
          <button
            class="del-btn dim-del"
            type="button"
            :aria-label="`删除维度 ${dimensionIndex + 1}`"
            @click="removeDimension(dimensionIndex)"
          >
            删除
          </button>
        </div>
        <input
          v-for="objectIndex in objects.length"
          :key="`cell-${dimensionIndex}-${objectIndex}`"
          v-model="cells[dimensionIndex][objectIndex - 1]"
          class="input cell"
          type="text"
          :aria-label="`第 ${dimensionIndex + 1} 行第 ${objectIndex} 列取值`"
        >
      </div>
      <button
        class="add-btn add-dimension"
        type="button"
        @click="addDimension"
      >
        添加维度
      </button>
    </div>
    <label class="field">
      <span class="field-label">引用索引(逗号分隔,指向下方来源序号,可空)</span>
      <input
        v-model="citationsRaw"
        class="input citations-input"
        type="text"
        placeholder="如:1,2"
      >
    </label>
    <p
      v-if="errors.length"
      class="field-error"
      role="alert"
    >
      {{ errors.join(';') }}
    </p>
  </section>
</template>

<style scoped>
.editor { display: flex; flex-direction: column; gap: 12px; }
.grid-wrap { display: flex; flex-direction: column; gap: 8px; }
.grid-row { display: flex; align-items: center; gap: 8px; }
.grid-head { padding-bottom: 4px; border-bottom: 1px solid var(--ke-line); }
.grid-corner { width: 150px; flex-shrink: 0; color: var(--ke-sub-2); font-size: 11px; }
.head-cell { display: flex; flex-direction: column; gap: 4px; width: 150px; flex-shrink: 0; }
.dim-cell { display: flex; align-items: center; gap: 4px; width: 150px; flex-shrink: 0; }
.dim-field { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.dim-input { width: 100%; box-sizing: border-box; }
.cell { width: 150px; flex-shrink: 0; }
.input { padding: 7px 10px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-s); background: var(--ke-surface); color: var(--ke-ink); font-size: 13px; box-sizing: border-box; }
.input:focus { outline: none; border-color: var(--ke-primary); box-shadow: var(--ke-focus); }
.add-btn, .del-btn { padding: 4px 10px; border: 1px dashed var(--ke-line-strong); border-radius: var(--ke-radius-s); background: none; color: var(--ke-sub); font-size: 12px; cursor: pointer; white-space: nowrap; transition: color var(--ke-dur-fast) var(--ke-ease), border-color var(--ke-dur-fast) var(--ke-ease); }
.add-btn:hover { border-color: var(--ke-primary); color: var(--ke-primary); }
.del-btn { border-style: solid; border-color: var(--ke-line); }
.del-btn:hover { border-color: var(--ke-danger); color: var(--ke-danger); }
.field { display: block; }
.field-label { display: block; margin-bottom: 6px; color: var(--ke-ink-2); font-size: 12px; }
.field-error { margin: 0; color: var(--ke-danger); font-size: 12px; }
.name-error { margin: 0; color: var(--ke-danger); font-size: 11px; line-height: 1.4; }
</style>
