<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { Component } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { KeIcon, THEMES } from '@ke/shared';
import { createCard, getWbCard, saveCardContent } from '../api/cards';
import type { CardStatus, CardTemplateType, SourceRef } from '../api/types';
import { STATUS_META, TEMPLATE_LABELS } from '../cardMeta';
import TextEditor from '../components/editors/TextEditor.vue';
import CompareEditor from '../components/editors/CompareEditor.vue';
import TimelineEditor from '../components/editors/TimelineEditor.vue';
import TaskEditor from '../components/editors/TaskEditor.vue';
import SourcesEditor from '../components/editors/SourcesEditor.vue';

/**
 * 四模板卡片编辑页:
 * - /cards/new 新建:theme/templateType/title 表头 + 编辑器 + 来源,POST /wb/cards 建卡+首版;
 * - /cards/edit/:id 编辑:getWbCard 回填(全状态可见),PUT content 存新版本(版本不可变)。
 * 保存前本地校验与后端同规则(编辑器行内错误 + 汇总门禁);后端 400/403 的
 * ApiError message(含字段路径)显示在页顶 alert。
 */
const route = useRoute();
const router = useRouter();

/** /cards/edit/:id → 编辑;/cards/new → 新建 */
const editId = route.params.id ? Number(route.params.id) : null;
const isNew = editId === null;

const TEMPLATE_OPTIONS: Array<{ value: CardTemplateType; label: string }> = [
  { value: 'TEXT', label: '图文卡' },
  { value: 'COMPARE', label: '对比卡' },
  { value: 'TIMELINE', label: '时间线卡' },
  { value: 'TASK', label: '任务卡' }
];

/** 各模板最小骨架(空表单态,由创作者补全后保存) */
function seedContent(type: CardTemplateType): Record<string, unknown> {
  switch (type) {
    case 'COMPARE':
      return { objects: ['', ''], dimensions: ['', ''], cells: [['', ''], ['', '']] };
    case 'TIMELINE':
      return { events: [{ year: '', title: '', body: '' }] };
    case 'TASK':
      return { goal: '', steps: [{ place: '', observe: '', minutes: 30 }], recordSchema: ['文本'] };
    default:
      return { summary: '', sections: [{ h: '', body: '' }], related: [] };
  }
}

const EDITORS: Record<CardTemplateType, Component> = {
  TEXT: TextEditor,
  COMPARE: CompareEditor,
  TIMELINE: TimelineEditor,
  TASK: TaskEditor
};

/** 专题取 @ke/shared THEMES 权威字典(3 键);新建默认选第一键(academy)。
 * 编辑态回填的是后端自由字符串(向前兼容历史数据),只读展示原样。 */
const theme = ref<string>(THEMES[0].key);
const title = ref('');
const templateType = ref<CardTemplateType>('TEXT');
const content = ref<Record<string, unknown>>(seedContent('TEXT'));
const sources = ref<SourceRef[]>([]);
const loadedStatus = ref('');
const loading = ref(false);
const loadError = ref('');
const saving = ref(false);
const saveError = ref('');

/** 回填状态 chip:loadedStatus 是自由串(接口回填),收窄到 CardStatus 后查 STATUS_META */
const statusMeta = computed(() =>
  (loadedStatus.value ? STATUS_META[loadedStatus.value as CardStatus] : null));

/** 模板切换/回填后自增,强制重挂载编辑器(编辑器以挂载时的 modelValue 初始化) */
const editorKey = ref(0);
const editorComponent = computed(() => EDITORS[templateType.value]);
const editorRef = ref<{ errors: string[] } | null>(null);
const sourcesRef = ref<{ errors: string[] } | null>(null);

function switchTemplate(type: CardTemplateType): void {
  if (templateType.value === type) {
    return;
  }
  templateType.value = type;
  content.value = seedContent(type);
  editorKey.value += 1;
}

onMounted(async () => {
  if (isNew) {
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    const card = await getWbCard(editId);
    theme.value = card.theme;
    title.value = card.title;
    templateType.value = card.templateType;
    content.value = card.content;
    sources.value = card.sources;
    loadedStatus.value = card.status;
    editorKey.value += 1;
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '卡片加载失败';
  } finally {
    loading.value = false;
  }
});

async function onSave(): Promise<void> {
  saveError.value = '';
  if (isNew) {
    const missing: string[] = [];
    if (!theme.value.trim()) {
      missing.push('请填写专题');
    }
    if (!title.value.trim()) {
      missing.push('请填写标题');
    }
    if (missing.length > 0) {
      saveError.value = missing.join(';');
      return;
    }
  }
  const fieldErrors = [...(editorRef.value?.errors ?? []), ...(sourcesRef.value?.errors ?? [])];
  if (fieldErrors.length > 0) {
    saveError.value = `请先修正 ${fieldErrors.length} 处表单错误:${fieldErrors[0]}${fieldErrors.length > 1 ? ' 等' : ''}`;
    return;
  }
  saving.value = true;
  try {
    if (isNew) {
      await createCard({
        theme: theme.value.trim(),
        templateType: templateType.value,
        title: title.value.trim(),
        content: content.value,
        sources: sources.value
      });
      ElMessage.success('卡片已创建');
    } else {
      await saveCardContent(editId, { content: content.value, sources: sources.value });
      ElMessage.success(loadedStatus.value === 'PUBLISHED' ? '已保存为新版本' : '内容已保存');
    }
    router.push('/cards');
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : '保存失败,请稍后重试';
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div class="head-left">
        <button
          class="back-btn"
          type="button"
          @click="router.push('/cards')"
        >
          <KeIcon name="back" />
          <span>返回</span>
        </button>
        <h2 class="page-title">
          {{ isNew ? '新建卡片' : '编辑卡片' }}
        </h2>
      </div>
      <button
        class="save-btn"
        type="button"
        :disabled="saving || loading"
        @click="onSave"
      >
        {{ saving ? '保存中…' : '保存' }}
      </button>
    </header>

    <p
      v-if="loadError"
      class="alert"
      role="alert"
    >
      {{ loadError }}
    </p>
    <p
      v-if="saveError"
      class="alert"
      role="alert"
    >
      {{ saveError }}
    </p>

    <p
      v-if="loading"
      class="hint"
    >
      加载中…
    </p>
    <template v-else>
      <section
        v-if="isNew"
        class="head-form"
      >
        <label class="field">
          <span class="field-label">专题</span>
          <select
            v-model="theme"
            class="input head-theme"
            aria-label="专题"
          >
            <option
              v-for="t in THEMES"
              :key="t.key"
              :value="t.key"
            >
              {{ t.label }}
            </option>
          </select>
        </label>
        <label class="field">
          <span class="field-label">模板</span>
          <select
            :value="templateType"
            class="input head-template"
            @change="switchTemplate(($event.target as HTMLSelectElement).value as CardTemplateType)"
          >
            <option
              v-for="option in TEMPLATE_OPTIONS"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </option>
          </select>
        </label>
        <label class="field grow">
          <span class="field-label">标题</span>
          <input
            v-model="title"
            class="input head-title"
            type="text"
            maxlength="120"
            placeholder="卡片标题"
          >
        </label>
      </section>
      <section
        v-else
        class="head-form readonly"
      >
        <p class="head-meta">
          <span class="head-theme-text">{{ theme }}</span>
          <span class="tpl-chip">{{ TEMPLATE_LABELS[templateType] ?? templateType }}</span>
          <span
            v-if="statusMeta"
            class="status-chip"
          >{{ statusMeta.label }}</span>
        </p>
        <h3 class="head-title-text">
          {{ title }}
        </h3>
        <p
          v-if="loadedStatus === 'PUBLISHED'"
          class="hint"
        >
          已发布卡片内容不可变,保存将生成新版本(发布后生效)。
        </p>
      </section>

      <component
        :is="editorComponent"
        :key="editorKey"
        ref="editorRef"
        v-model:model-value="content"
        :sources-count="sources.length"
      />

      <section class="sources-block">
        <h3 class="sources-title">
          来源(citations 按行序 1-based 引用)
        </h3>
        <SourcesEditor
          ref="sourcesRef"
          v-model:model-value="sources"
        />
      </section>
    </template>
  </section>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; max-width: 860px; }
.page-head { display: flex; align-items: center; justify-content: space-between; }
.head-left { display: flex; align-items: center; gap: 10px; }
.back-btn { display: flex; align-items: center; gap: 4px; padding: 5px 10px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-s); background: none; color: var(--ke-sub); font-size: 13px; cursor: pointer; transition: color var(--ke-dur-fast) var(--ke-ease), border-color var(--ke-dur-fast) var(--ke-ease); }
.back-btn:hover { border-color: var(--ke-line-strong); color: var(--ke-ink); }
.back-btn .ke-icon { width: 14px; height: 14px; }
.page-title { margin: 0; color: var(--ke-ink); font-size: 18px; }

/* 页标题可用宋体(04 文档字体分层),正文输入一律默认黑体 */
.save-btn { height: 34px; padding: 0 20px; border: none; border-radius: var(--ke-radius-s); background: var(--ke-primary); color: var(--ke-white); font-size: 13px; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); }
.save-btn:hover { background: var(--ke-primary-deep); }
.save-btn:disabled { opacity: 0.6; cursor: default; }
.alert { margin: 0; padding: 8px 12px; border-radius: var(--ke-radius-s); background: var(--ke-danger-soft); color: var(--ke-danger); font-size: 13px; }
.hint { margin: 0; color: var(--ke-sub-2); font-size: 12px; }
.head-form { display: flex; gap: 12px; }
.head-form.readonly { flex-direction: column; gap: 6px; }
.head-form .field { display: block; }
.head-form .grow { flex: 1; }
.field-label { display: block; margin-bottom: 6px; color: var(--ke-ink-2); font-size: 12px; }
.input { width: 100%; height: 36px; padding: 0 10px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-s); background: var(--ke-surface); color: var(--ke-ink); font-size: 13px; box-sizing: border-box; }
.input:focus { outline: none; border-color: var(--ke-primary); box-shadow: var(--ke-focus); }
.head-theme { width: 160px; }
.head-template { width: 140px; }
.head-meta { display: flex; align-items: center; gap: 8px; margin: 0; color: var(--ke-sub); font-size: 12px; }
.head-theme-text { color: var(--ke-ink-2); }
.tpl-chip { padding: 2px 8px; border-radius: var(--ke-radius-full); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 11px; font-weight: 600; }
.status-chip { padding: 2px 8px; border-radius: var(--ke-radius-full); background: var(--ke-surface-2); color: var(--ke-sub); font-size: 11px; }
.head-title-text { margin: 0; color: var(--ke-ink); font-family: var(--ke-font-display); font-size: 20px; font-weight: 700; }
.sources-block { display: flex; flex-direction: column; gap: 8px; padding-top: 4px; border-top: 1px solid var(--ke-line); }
.sources-title { margin: 0; color: var(--ke-ink-2); font-size: 13px; font-weight: 600; }
</style>
