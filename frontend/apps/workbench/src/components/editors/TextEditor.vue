<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ApiError } from '../../api/http';
import { uploadImage } from '../../api/assets';
import { citationsError, citationsOf } from './citations';

/**
 * 图文卡编辑器(FR-C03):summary ≤120、sections(h/body/citations)增删、related 可选、
 * image 可选配图(二期图片功能:上传落本地服务器,content 引用 /api/images/{id})。
 * v-model:modelValue 为 TextCardContent 形状的 content 对象;每次变更即时 emit 规范化载荷;
 * 行内校验与后端 CardContentValidator 同规则(summary 非空 ≤120、h/body 非空、citations 1-based)。
 */
interface SectionRow {
  h: string;
  body: string;
  citationsRaw: string;
}
interface RelatedRow {
  cardIdRaw: string;
  relation: string;
  why: string;
  sourceRaw: string;
}
interface ImageState {
  id: number;
  url: string;
  alt: string;
}

const props = defineProps<{ modelValue: Record<string, unknown>; sourcesCount: number }>();
const emit = defineEmits<{ (e: 'update:modelValue', value: Record<string, unknown>): void }>();

const SUMMARY_MAX = 120;
/** 关系词四选一(与后端 RelationType.labels() 白名单一致) */
const RELATION_OPTIONS = ['深入了解', '相关联', '相比较', '去实践'] as const;
const IMAGE_ACCEPT = 'image/jpeg,image/png,image/gif,image/webp';

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' ? (value as Record<string, unknown>) : {};
}
function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}
function asNumbers(value: unknown): number[] {
  return Array.isArray(value) ? value.filter((n): n is number => typeof n === 'number') : [];
}

const summary = ref(asString(props.modelValue.summary));
const sections = reactive<SectionRow[]>(
  (Array.isArray(props.modelValue.sections) && props.modelValue.sections.length > 0
    ? (props.modelValue.sections as unknown[]).map(asRecord)
    : [{}]
  ).map((section) => ({
    h: asString(section.h),
    body: asString(section.body),
    citationsRaw: asNumbers(section.citations).join(',')
  }))
);
const related = reactive<RelatedRow[]>(
  (Array.isArray(props.modelValue.related) ? (props.modelValue.related as unknown[]).map(asRecord) : []).map((row) => ({
    cardIdRaw: row.cardId == null ? '' : String(row.cardId),
    relation: asString(row.relation),
    why: asString(row.why),
    sourceRaw: row.source == null ? '' : String(row.source)
  }))
);

function addSection(): void {
  sections.push({ h: '', body: '', citationsRaw: '' });
}
function removeSection(index: number): void {
  sections.splice(index, 1);
}
function addRelated(): void {
  related.push({ cardIdRaw: '', relation: '', why: '', sourceRaw: '' });
}
function removeRelated(index: number): void {
  related.splice(index, 1);
}

// —— 配图(二期图片功能):回填已有 → 上传换图 → alt 说明 → 移除;错误并入 errors 门禁 ——
function asImage(value: unknown): ImageState | null {
  const record = asRecord(value);
  const id = record.id;
  const url = asString(record.url);
  return typeof id === 'number' && url !== '' ? { id, url, alt: asString(record.alt) } : null;
}
const image = ref<ImageState | null>(asImage(props.modelValue.image));
const imageUploading = ref(false);
const imageError = ref('');

async function onImagePick(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0] ?? null;
  input.value = ''; // 清空让同一文件可重选
  if (!file || imageUploading.value) return;
  imageError.value = '';
  imageUploading.value = true;
  try {
    const uploaded = await uploadImage(file);
    image.value = { id: uploaded.id, url: uploaded.url, alt: image.value?.alt ?? '' };
  } catch (e) {
    imageError.value = e instanceof ApiError ? e.message : '上传失败,请重试';
  } finally {
    imageUploading.value = false;
  }
}
function removeImage(): void {
  image.value = null;
  imageError.value = '';
}

const summaryError = computed(() => {
  if (!summary.value.trim()) {
    return '请填写摘要';
  }
  if (summary.value.length > SUMMARY_MAX) {
    return `摘要不能超过 ${SUMMARY_MAX} 字(当前 ${summary.value.length} 字)`;
  }
  return '';
});

function sectionErrors(index: number): string[] {
  const row = sections[index];
  const list: string[] = [];
  if (!row.h.trim()) {
    list.push(`sections[${index}].h: 请填写小节标题`);
  }
  if (!row.body.trim()) {
    list.push(`sections[${index}].body: 请填写小节正文`);
  }
  const citationError = citationsError(row.citationsRaw, props.sourcesCount);
  if (citationError) {
    list.push(`sections[${index}].citations: ${citationError}`);
  }
  return list;
}

function relatedErrors(index: number): string[] {
  const row = related[index];
  const list: string[] = [];
  if (!/^\d+$/.test(row.cardIdRaw)) {
    list.push(`related[${index}].cardId: 请填写数字卡片 id`);
  }
  if (!row.relation) {
    list.push(`related[${index}].relation: 请选择关系词`);
  }
  if (!row.why.trim()) {
    list.push(`related[${index}].why: 请填写关联理由`);
  }
  if (row.sourceRaw !== '' && !/^\d+$/.test(row.sourceRaw)) {
    list.push(`related[${index}].source: 出处序号须为数字`);
  }
  return list;
}

const errors = computed<string[]>(() => {
  const list: string[] = [];
  if (summaryError.value) {
    list.push(`summary: ${summaryError.value}`);
  }
  sections.forEach((_, index) => list.push(...sectionErrors(index)));
  related.forEach((_, index) => list.push(...relatedErrors(index)));
  if (imageError.value) {
    list.push(`image: ${imageError.value}`);
  }
  return list;
});

const payload = computed<Record<string, unknown>>(() => ({
  summary: summary.value,
  sections: sections.map((row) => {
    const citations = citationsOf(row.citationsRaw, props.sourcesCount);
    return citations === undefined ? { h: row.h, body: row.body } : { h: row.h, body: row.body, citations };
  }),
  related: related.map((row) => {
    const item: Record<string, unknown> = { cardId: Number(row.cardIdRaw), relation: row.relation, why: row.why };
    if (row.sourceRaw !== '') {
      item.source = Number(row.sourceRaw);
    }
    return item;
  }),
  ...(image.value
    ? {
        image: {
          id: image.value.id,
          url: image.value.url,
          ...(image.value.alt.trim() === '' ? {} : { alt: image.value.alt.trim() })
        }
      }
    : {})
}));

watch(payload, (value) => emit('update:modelValue', value), { immediate: true });
defineExpose({ errors });
</script>

<template>
  <section class="editor">
    <label class="field">
      <span class="field-label">
        摘要
        <span class="char-count">{{ summary.length }}/120</span>
      </span>
      <textarea
        v-model="summary"
        class="input area summary-input"
        rows="3"
        placeholder="一句话概括本卡内容(≤120 字)"
      />
      <span
        v-if="summaryError"
        class="field-error summary-error"
      >{{ summaryError }}</span>
    </label>

    <fieldset class="block">
      <legend class="block-title">
        配图(可选)
      </legend>
      <div
        v-if="image"
        class="image-row"
      >
        <img
          :src="image.url"
          :alt="image.alt === '' ? '卡片配图' : image.alt"
          class="image-thumb"
        >
        <div class="image-side">
          <label class="field">
            <span class="field-label">图片说明(alt,可空)</span>
            <input
              v-model="image.alt"
              class="input"
              type="text"
              maxlength="60"
              placeholder="一句话描述图片内容"
            >
          </label>
          <button
            class="del-btn"
            type="button"
            @click="removeImage"
          >
            移除配图
          </button>
        </div>
      </div>
      <label
        v-else
        class="upload-btn"
        :class="{ busy: imageUploading }"
      >
        <input
          class="upload-input"
          type="file"
          :accept="IMAGE_ACCEPT"
          :disabled="imageUploading"
          @change="onImagePick"
        >
        {{ imageUploading ? '上传中…' : '上传配图(JPG/PNG/GIF/WebP,≤5MB)' }}
      </label>
      <p
        v-if="imageError"
        class="field-error"
        role="alert"
      >
        {{ imageError }}
      </p>
    </fieldset>

    <fieldset class="block">
      <legend class="block-title">
        小节
      </legend>
      <div
        v-for="(row, index) in sections"
        :key="index"
        class="row-card"
      >
        <label class="field">
          <span class="field-label">标题</span>
          <input
            v-model="row.h"
            class="input section-h"
            type="text"
            placeholder="小节标题"
          >
        </label>
        <label class="field">
          <span class="field-label">正文</span>
          <textarea
            v-model="row.body"
            class="input area section-body"
            rows="3"
            placeholder="小节正文"
          />
        </label>
        <label class="field">
          <span class="field-label">引用索引(逗号分隔,指向下方来源序号,可空)</span>
          <input
            v-model="row.citationsRaw"
            class="input citations-input"
            type="text"
            placeholder="如:1,2"
          >
        </label>
        <p
          v-for="message in sectionErrors(index)"
          :key="message"
          class="field-error"
          role="alert"
        >
          {{ message }}
        </p>
        <button
          class="del-btn"
          type="button"
          @click="removeSection(index)"
        >
          删除小节
        </button>
      </div>
      <button
        class="add-btn"
        type="button"
        @click="addSection"
      >
        添加小节
      </button>
    </fieldset>

    <fieldset class="block">
      <legend class="block-title">
        关联卡片(可选)
      </legend>
      <div
        v-for="(row, index) in related"
        :key="index"
        class="row-card"
      >
        <div class="grid-2">
          <label class="field">
            <span class="field-label">卡片 id</span>
            <input
              v-model="row.cardIdRaw"
              class="input"
              type="text"
              placeholder="关联卡 id"
            >
          </label>
          <label class="field">
            <span class="field-label">关系词</span>
            <select
              v-model="row.relation"
              class="input"
            >
              <option
                value=""
                disabled
              >
                请选择
              </option>
              <option
                v-for="option in RELATION_OPTIONS"
                :key="option"
                :value="option"
              >
                {{ option }}
              </option>
            </select>
          </label>
          <label class="field">
            <span class="field-label">关联理由</span>
            <input
              v-model="row.why"
              class="input"
              type="text"
              placeholder="为什么关联"
            >
          </label>
          <label class="field">
            <span class="field-label">出处序号(可空)</span>
            <input
              v-model="row.sourceRaw"
              class="input"
              type="text"
              placeholder="指向来源序号"
            >
          </label>
        </div>
        <p
          v-for="message in relatedErrors(index)"
          :key="message"
          class="field-error"
          role="alert"
        >
          {{ message }}
        </p>
        <button
          class="del-btn"
          type="button"
          @click="removeRelated(index)"
        >
          删除关联
        </button>
      </div>
      <button
        class="add-btn"
        type="button"
        @click="addRelated"
      >
        添加关联
      </button>
    </fieldset>
  </section>
</template>

<style scoped>
.editor { display: flex; flex-direction: column; gap: 16px; }
.field { display: block; }
.field-label { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; color: var(--ke-ink-2); font-size: 12px; }
.char-count { color: var(--ke-sub-2); font-variant-numeric: tabular-nums; }
.input { width: 100%; padding: 7px 10px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-s); background: var(--ke-surface); color: var(--ke-ink); font-size: 13px; box-sizing: border-box; }
.input:focus { outline: none; border-color: var(--ke-primary); box-shadow: var(--ke-focus); }
.area { resize: vertical; }
.field-error { margin: 4px 0 0; color: var(--ke-danger); font-size: 12px; }
.block { margin: 0; padding: 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); display: flex; flex-direction: column; gap: 10px; }
.block-title { padding: 0 6px; color: var(--ke-ink); font-size: 13px; font-weight: 600; }
.row-card { padding: 12px; border: 1px solid var(--ke-line-2); border-radius: var(--ke-radius-s); background: var(--ke-surface-2); display: flex; flex-direction: column; gap: 10px; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.add-btn, .del-btn { align-self: flex-start; padding: 5px 12px; border: 1px dashed var(--ke-line-strong); border-radius: var(--ke-radius-s); background: none; color: var(--ke-sub); font-size: 12px; cursor: pointer; transition: color var(--ke-dur-fast) var(--ke-ease), border-color var(--ke-dur-fast) var(--ke-ease); }
.add-btn:hover { border-color: var(--ke-primary); color: var(--ke-primary); }
.del-btn { border-style: solid; border-color: var(--ke-line); }
.del-btn:hover { border-color: var(--ke-danger); color: var(--ke-danger); }
.image-row { display: flex; gap: 12px; align-items: flex-start; }
.image-thumb { flex: 0 0 auto; width: 132px; height: 88px; object-fit: cover; border: 1px solid var(--ke-line-2); border-radius: var(--ke-radius-s); background: var(--ke-surface-2); }
.image-side { display: flex; flex: 1; flex-direction: column; gap: 10px; }
.upload-btn { position: relative; display: flex; align-items: center; justify-content: center; padding: 18px; border: 1.5px dashed var(--ke-line-strong); border-radius: var(--ke-radius-s); color: var(--ke-sub); font-size: 12px; cursor: pointer; transition: color var(--ke-dur-fast) var(--ke-ease), border-color var(--ke-dur-fast) var(--ke-ease); }
.upload-btn:hover, .upload-btn.busy { border-color: var(--ke-primary); color: var(--ke-primary); }
.upload-btn.busy { cursor: default; opacity: 0.7; }
.upload-input { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
</style>
