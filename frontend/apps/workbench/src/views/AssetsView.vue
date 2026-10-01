<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { KeIcon } from '@ke/shared';
import { importAssets, listAssets, listCitations } from '../api/assets';
import { ApiError } from '../api/http';
import type { AssetImportError, AssetItem, AssetKind, CitationItem } from '../api/types';
import { useAuthStore } from '../stores/auth';
import WbDenied from '../components/WbDenied.vue';

/**
 * 知识资源管理页(FR-O01/O02):
 * CSV 批量导入(仅 EDITOR/OPERATOR,部分成功 = 已导入行提示 + 可折叠失败行清单)、
 * 列表(kind 筛选 + 标题检索 + offset 分页)、授权状态/到期、被引次数(点开引用明细抽屉)。
 * CSV 列格式:kind,title,source_meta,locator,license,license_expire,content_extract
 * (locator/source_meta 为 JSON 字符串,可参考后端仓库 seed/assets-book.csv)。
 */
const auth = useAuthStore();

/** 与后端 offset 契约一致:size ≤ 100,默认 20 */
const PAGE_SIZE = 20;

/** 类型 → chip 文案与 el-tag 内置 type(不写自定义色,04 §2.4 同源做法) */
const KIND_META: Record<AssetKind, { label: string; tagType: 'primary' | 'success' | 'warning' | 'danger' }> = {
  book: { label: '书', tagType: 'primary' },
  article: { label: '文章', tagType: 'success' },
  audio: { label: '音频', tagType: 'warning' },
  video: { label: '视频', tagType: 'danger' }
};

/** kind 下拉选项('' = 全部) */
const KIND_OPTIONS = [
  { label: '全部', value: '' },
  { label: '书', value: 'book' },
  { label: '文章', value: 'article' },
  { label: '音频', value: 'audio' },
  { label: '视频', value: 'video' }
] as const;

/** CSV 导入仅 EDITOR/OPERATOR(与后端方法级 RBAC 同则) */
const canImport = computed(() => {
  const role = auth.user?.role;
  return role === 'EDITOR' || role === 'OPERATOR';
});

const activeKind = ref<'' | AssetKind>('');
const keyword = ref('');
const rows = ref<AssetItem[]>([]);
const total = ref(0);
const page = ref(1);
const loading = ref(false);
const errorMsg = ref('');
/** 后端 403(EXPLORER 越权)→ 权限空态 */
const denied = ref(false);

async function load(): Promise<void> {
  loading.value = true;
  errorMsg.value = '';
  try {
    const data = await listAssets({
      kind: activeKind.value,
      q: keyword.value.trim(),
      page: page.value,
      size: PAGE_SIZE
    });
    rows.value = data.items;
    total.value = data.total;
  } catch (e) {
    if (e instanceof ApiError && e.code === 403) {
      denied.value = true;
      rows.value = [];
    } else {
      errorMsg.value = e instanceof Error ? e.message : '加载失败,请稍后重试';
    }
  } finally {
    loading.value = false;
  }
}

function onKindChange(): void {
  page.value = 1;
  void load();
}

/** 搜索防抖 300ms,输入停顿后才检索 */
let searchTimer: ReturnType<typeof setTimeout> | undefined;
function onSearchInput(): void {
  if (searchTimer !== undefined) {
    clearTimeout(searchTimer);
  }
  searchTimer = setTimeout(() => {
    searchTimer = undefined;
    page.value = 1;
    void load();
  }, 300);
}
onBeforeUnmount(() => {
  if (searchTimer !== undefined) {
    clearTimeout(searchTimer);
  }
});

function onPageChange(value: number): void {
  page.value = value;
  void load();
}

// ---------- CSV 导入(FR-O01) ----------

const fileInput = ref<HTMLInputElement | null>(null);
const importing = ref(false);
/** 最近一次导入的失败行(部分成功语义:不影响已导入行的成功提示) */
const importErrors = ref<AssetImportError[]>([]);
/** 错误折叠面板默认展开,导入完即可见 */
const openErrorPanel = ref<string[]>([]);

function pickFile(): void {
  fileInput.value?.click();
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = ''; // 允许重复选择同一文件再次导入
  if (!file) {
    return;
  }
  importing.value = true;
  try {
    const result = await importAssets(file);
    importErrors.value = result.errors ?? [];
    openErrorPanel.value = importErrors.value.length > 0 ? ['errors'] : [];
    if (result.imported > 0) {
      ElMessage.success(`导入 ${result.imported} 条`);
    } else if (importErrors.value.length > 0) {
      ElMessage.warning(`没有可导入的行,${importErrors.value.length} 行校验失败`);
    }
    page.value = 1;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导入失败,请稍后重试');
  } finally {
    importing.value = false;
  }
}

// ---------- 摘要与引用明细(FR-O02) ----------

/** 对象 JSON → 「k:v k:v」摘要;空值兜底 — */
function jsonSummary(value: Record<string, unknown> | null): string {
  const entries = Object.entries(value ?? {});
  if (entries.length === 0) {
    return '—';
  }
  return entries.map(([k, v]) => `${k}:${String(v)}`).join(' ');
}

/** 定位器摘要:chapter 或 t 优先,其余整体摘要 */
function locatorSummary(value: Record<string, unknown> | null): string {
  if (value == null) {
    return '—';
  }
  if (value.chapter != null) {
    return String(value.chapter);
  }
  if (value.t != null) {
    return String(value.t);
  }
  return jsonSummary(value);
}

const citeOpen = ref(false);
const citeAsset = ref<AssetItem | null>(null);
const citations = ref<CitationItem[]>([]);
const citeLoading = ref(false);

async function openCitations(row: AssetItem): Promise<void> {
  citeAsset.value = row;
  citeOpen.value = true;
  citeLoading.value = true;
  citations.value = [];
  try {
    citations.value = await listCitations(row.id);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '引用加载失败');
  } finally {
    citeLoading.value = false;
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h2 class="page-title">
        知识资源
      </h2>
      <button
        v-if="canImport"
        class="import-btn"
        type="button"
        :disabled="importing"
        title="导入知识单元 CSV;列:kind,title,source_meta,locator,license,license_expire,content_extract(locator 为 JSON 字符串)"
        @click="pickFile"
      >
        <KeIcon name="plus" />
        <span>{{ importing ? '导入中…' : '导入 CSV' }}</span>
      </button>
      <input
        v-if="canImport"
        ref="fileInput"
        class="file-input"
        type="file"
        accept=".csv,text/csv"
        @change="onFileChange"
      >
    </header>

    <div class="toolbar">
      <el-select
        v-model="activeKind"
        class="kind-select"
        :teleported="false"
        aria-label="类型筛选"
        @change="onKindChange"
      >
        <el-option
          v-for="opt in KIND_OPTIONS"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
          :data-kind="opt.value || 'ALL'"
        />
      </el-select>
      <el-input
        v-model="keyword"
        class="search"
        placeholder="按标题搜索"
        clearable
        @input="onSearchInput"
      >
        <template #prefix>
          <KeIcon name="search" />
        </template>
      </el-input>
    </div>

    <p
      v-if="errorMsg"
      class="load-error"
      role="alert"
    >
      {{ errorMsg }}
    </p>

    <el-collapse
      v-if="importErrors.length > 0"
      v-model="openErrorPanel"
      class="import-errors"
    >
      <el-collapse-item
        name="errors"
        :title="`本次导入有 ${importErrors.length} 行未通过校验(点击展开/收起)`"
      >
        <ul class="import-error-list">
          <li
            v-for="err in importErrors"
            :key="`${err.line}-${err.reason}`"
          >
            第 {{ err.line }} 行:{{ err.reason }}
          </li>
        </ul>
      </el-collapse-item>
    </el-collapse>

    <WbDenied v-if="denied">
      当前角色无权查看知识资源(需创作者/编辑/运营)。
    </WbDenied>

    <template v-else>
      <el-table
        v-loading="loading"
        class="assets-table"
        :data="rows"
        :empty-text="canImport ? '暂无数据,通过上方按钮导入知识单元 CSV' : '暂无知识资源'"
      >
        <el-table-column
          prop="title"
          label="标题"
          min-width="200"
          show-overflow-tooltip
        />
        <el-table-column
          label="类型"
          width="80"
        >
          <template #default="{ row }">
            <el-tag
              class="kind-tag"
              :type="KIND_META[row.kind as AssetKind]?.tagType ?? 'info'"
              size="small"
              disable-transitions
            >
              {{ KIND_META[row.kind as AssetKind]?.label ?? row.kind }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="来源"
          min-width="180"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ jsonSummary(row.sourceMeta) }}
          </template>
        </el-table-column>
        <el-table-column
          label="定位"
          min-width="150"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ locatorSummary(row.locator) }}
          </template>
        </el-table-column>
        <el-table-column
          label="授权"
          width="200"
        >
          <template #default="{ row }">
            <span class="license-cell">
              <template v-if="row.license">
                {{ row.license }}<template v-if="row.licenseExpire"> · {{ row.licenseExpire }}</template>
              </template>
              <template v-else>
                —
              </template>
              <el-tag
                v-if="row.expired"
                class="expire-tag"
                type="danger"
                size="small"
                disable-transitions
              >
                已到期
              </el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column
          width="110"
        >
          <template #header>
            <span class="cite-head">
              被引次数
              <el-tooltip
                content="该知识单元被卡片版本/智能体运行引用的次数;点击数字查看引用明细"
                placement="top"
              >
                <KeIcon
                  name="share"
                  class="cite-help"
                />
              </el-tooltip>
            </span>
          </template>
          <template #default="{ row }">
            <button
              class="cite-count num"
              type="button"
              @click="openCitations(row)"
            >
              {{ row.citationCount }}
            </button>
          </template>
        </el-table-column>
      </el-table>

      <footer class="page-foot">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :page-size="PAGE_SIZE"
          :current-page="page"
          @current-change="onPageChange"
        />
      </footer>
    </template>

    <el-drawer
      v-model="citeOpen"
      class="cite-drawer"
      :title="`引用明细 · ${citeAsset?.title ?? ''}`"
      size="420px"
    >
      <p
        v-if="citeLoading"
        class="cite-tip"
      >
        加载中…
      </p>
      <template v-else>
        <ul
          v-if="citations.length > 0"
          class="citation-list"
        >
          <li
            v-for="c in citations"
            :key="c.id"
            class="citation-item"
          >
            <div class="cite-ref">
              <el-tag
                size="small"
                type="info"
                disable-transitions
              >
                {{ c.objectType }}
              </el-tag>
              <span class="num">#{{ c.objectId }}</span>
            </div>
            <p class="cite-quote">
              {{ c.quote ?? '—' }}
            </p>
          </li>
        </ul>
        <p
          v-else
          class="cite-tip"
        >
          暂无引用记录
        </p>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 14px; }
.page-head { display: flex; align-items: center; justify-content: flex-end; gap: 10px; position: relative; }
.page-title { margin: 0; margin-right: auto; color: var(--ke-ink); font-size: 18px; }
.import-btn { display: flex; align-items: center; gap: 6px; height: 34px; padding: 0 14px; border: none; border-radius: var(--ke-radius-s); background: var(--ke-primary); color: var(--ke-white); font-size: 13px; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); }
.import-btn:hover { background: var(--ke-primary-deep); }
.import-btn:disabled { opacity: 0.6; cursor: default; }
.import-btn .ke-icon { width: 16px; height: 16px; }
.file-input { display: none; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.kind-select { width: 130px; }
.search { width: 220px; }
.load-error { margin: 0; padding: 8px 12px; border-radius: var(--ke-radius-s); background: var(--ke-danger-soft); color: var(--ke-danger); font-size: 13px; }
.import-errors { border-radius: var(--ke-radius-s); }
.import-error-list { margin: 0; padding-left: 18px; color: var(--ke-danger); font-size: 12px; line-height: 1.9; }
.license-cell { display: inline-flex; align-items: center; gap: 6px; }
.expire-tag { flex-shrink: 0; }
.num { font-variant-numeric: tabular-nums; }
.cite-head { display: inline-flex; align-items: center; gap: 4px; }
.cite-help { width: 14px; height: 14px; color: var(--ke-sub); cursor: help; }
.cite-count { padding: 2px 8px; border: none; border-radius: var(--ke-radius-full); background: transparent; color: var(--ke-primary); font-size: 13px; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); }
.cite-count:hover { background: var(--ke-primary-soft); }
.cite-tip { margin: 0; color: var(--ke-sub); font-size: 13px; }
.citation-list { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 12px; }
.citation-item { padding: 10px 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-s); background: var(--ke-surface); }
.cite-ref { display: flex; align-items: center; gap: 8px; color: var(--ke-sub); font-size: 12px; }
.cite-quote { margin: 8px 0 0; color: var(--ke-ink); font-size: 13px; line-height: 1.7; }
.page-foot { display: flex; justify-content: flex-end; }
</style>
