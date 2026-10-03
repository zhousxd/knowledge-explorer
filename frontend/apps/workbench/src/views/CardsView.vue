<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { KeIcon } from '@ke/shared';
import { disableCard, listCards, submitCard } from '../api/cards';
import type { CardListItem, CardStatus } from '../api/types';
import { STATUS_META, TEMPLATE_LABELS } from '../cardMeta';
import { formatDateTime } from '../format';
import { useAuthStore } from '../stores/auth';
import CardDrawer from '../components/CardDrawer.vue';

const auth = useAuthStore();
const router = useRouter();

/** 与后端 offset 契约一致:size ≤ 100,默认 20 */
const PAGE_SIZE = 20;

/**
 * 是否可操作该行(送审/停用),与后端归属过滤同则(01 文档 RBAC):
 * EDITOR/OPERATOR 全量;CREATOR 仅自己名下(maintainerId 匹配);未登录不显示。
 */
function canManage(row: CardListItem): boolean {
  const user = auth.user;
  if (!user) {
    return false;
  }
  if (user.role === 'EDITOR' || user.role === 'OPERATOR') {
    return true;
  }
  return row.maintainerId != null && user.id === row.maintainerId;
}

/** 状态筛选 chips('' = 全部) */
const STATUS_TABS = [
  { label: '全部', value: '' },
  { label: '草稿', value: 'DRAFT' },
  { label: '待审核', value: 'PENDING' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '已停用', value: 'DISABLED' }
] as const;

const activeStatus = ref<'' | CardStatus>('');
const keyword = ref('');
const rows = ref<CardListItem[]>([]);
const total = ref(0);
const page = ref(1);
const loading = ref(false);
const errorMsg = ref('');

async function load(): Promise<void> {
  loading.value = true;
  errorMsg.value = '';
  try {
    const data = await listCards({
      status: activeStatus.value,
      q: keyword.value.trim(),
      page: page.value,
      size: PAGE_SIZE
    });
    rows.value = data.items;
    total.value = data.total;
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载失败,请稍后重试';
  } finally {
    loading.value = false;
  }
}

function switchStatus(value: '' | CardStatus): void {
  if (activeStatus.value === value) {
    return;
  }
  activeStatus.value = value;
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

// ---------- 行操作 ----------

const drawerOpen = ref(false);
const current = ref<CardListItem | null>(null);

function openDrawer(row: CardListItem): void {
  current.value = row;
  drawerOpen.value = true;
}

/** 行操作进行中的卡 id:期间操作按钮禁用、重复触发早退,防双击重复送审/停用 */
const busyId = ref<number | null>(null);

async function onSubmit(row: CardListItem): Promise<void> {
  if (busyId.value !== null) {
    return;
  }
  busyId.value = row.id;
  try {
    try {
      await submitCard(row.id);
      ElMessage.success(`已送审「${row.title}」`);
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '送审失败');
      return;
    }
    await load();
  } finally {
    busyId.value = null;
  }
}

async function onDisable(row: CardListItem): Promise<void> {
  if (busyId.value !== null) {
    return;
  }
  busyId.value = row.id;
  try {
    try {
      await ElMessageBox.confirm(`停用后探索端将不再展示「${row.title}」,确定停用?`, '停用卡片', {
        type: 'warning',
        confirmButtonText: '停用',
        cancelButtonText: '取消'
      });
    } catch {
      return; // 用户取消
    }
    try {
      await disableCard(row.id);
      ElMessage.success(`已停用「${row.title}」`);
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '停用失败');
      return;
    }
    await load();
  } finally {
    busyId.value = null;
  }
}

/** 编辑走独立路由页(四模板编辑器):非 DISABLED 行均可进入,PUBLISHED 保存即新版本 */
function onEdit(id: number): void {
  drawerOpen.value = false;
  router.push(`/cards/edit/${id}`);
}

// ---------- 新建:跳独立编辑页 ----------

function goCreate(): void {
  router.push('/cards/new');
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h2 class="page-title">
        卡片管理
      </h2>
      <button
        class="create-btn"
        type="button"
        @click="goCreate"
      >
        <KeIcon name="plus" />
        <span>新建卡片</span>
      </button>
    </header>

    <div class="toolbar">
      <div
        class="status-tabs"
        role="tablist"
        aria-label="状态筛选"
      >
        <button
          v-for="tab in STATUS_TABS"
          :key="tab.value"
          class="status-chip"
          :class="{ 'is-active': activeStatus === tab.value }"
          type="button"
          role="tab"
          :aria-selected="activeStatus === tab.value"
          :data-status="tab.value || 'ALL'"
          @click="switchStatus(tab.value)"
        >
          <span
            v-if="tab.value"
            class="chip-dot"
            :class="`dot-${STATUS_META[tab.value].tagType}`"
            aria-hidden="true"
          />
          {{ tab.label }}
        </button>
      </div>
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

    <el-table
      v-loading="loading"
      class="cards-table"
      :data="rows"
      empty-text="暂无卡片"
      @row-click="openDrawer"
    >
      <el-table-column
        prop="title"
        label="标题"
        min-width="200"
        show-overflow-tooltip
      />
      <el-table-column
        label="模板"
        width="90"
      >
        <template #default="{ row }">
          <span class="tpl-chip">{{ TEMPLATE_LABELS[row.templateType] ?? row.templateType }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="92"
      >
        <template #default="{ row }">
          <el-tag
            :type="STATUS_META[row.status as CardStatus].tagType"
            size="small"
            disable-transitions
          >
            {{ STATUS_META[row.status as CardStatus].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="版本"
        width="76"
      >
        <template #default="{ row }">
          <span class="num">v{{ row.currentVersionNo ?? '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="维护人"
        width="110"
      >
        <template #default="{ row }">
          {{ row.maintainerNickname ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="更新时间"
        width="150"
      >
        <template #default="{ row }">
          <span class="num">{{ formatDateTime(row.updatedAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="170"
      >
        <template #default="{ row }">
          <el-button
            class="act-detail"
            link
            type="primary"
            @click.stop="openDrawer(row)"
          >
            详情
          </el-button>
          <el-button
            v-if="row.status === 'DRAFT' && canManage(row)"
            class="act-submit"
            link
            type="primary"
            :disabled="busyId !== null"
            @click.stop="onSubmit(row)"
          >
            送审
          </el-button>
          <el-button
            v-if="row.status === 'PUBLISHED' && canManage(row)"
            class="act-disable"
            link
            type="danger"
            :disabled="busyId !== null"
            @click.stop="onDisable(row)"
          >
            停用
          </el-button>
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

    <CardDrawer
      :card="current"
      :open="drawerOpen"
      @update:open="drawerOpen = $event"
      @edit="onEdit"
    />
  </section>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 14px; }
.page-head { display: flex; align-items: center; justify-content: space-between; padding-bottom: 8px; gap: 16px; }
.page-title { margin: 0; color: var(--ke-ink); font-size: 18px; }
.create-btn { display: flex; align-items: center; gap: 6px; height: 34px; padding: 0 14px; border: none; background: var(--ke-primary); color: var(--ke-white); font-size: 13px; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); min-height: 42px; border-radius: var(--ke-radius-xs); }
.create-btn:hover { background: var(--ke-primary-deep); }
.create-btn .ke-icon { width: 16px; height: 16px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 20px; flex-wrap: wrap; padding-bottom: 16px; border-bottom: 1px solid var(--ke-line); }
.status-tabs { display: flex; gap: 20px; flex-wrap: wrap; }
.status-chip { display: inline-flex; align-items: center; gap: 6px; height: 28px; font-size: 12px; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease), color var(--ke-dur-fast) var(--ke-ease), border-color var(--ke-dur-fast) var(--ke-ease); min-height: 44px; padding: 8px 0; border: none; border-bottom: 2px solid transparent; border-radius: 0; background: transparent; color: var(--ke-sub); }
.status-chip:hover { border-color: var(--ke-primary); color: var(--ke-primary); }
.status-chip.is-active { border-color: var(--ke-primary); border-bottom-color: var(--ke-primary); background: transparent; color: var(--ke-ink); }

/* 筛选 chip 的状态语义色点:取 Element 主题变量(theme-element.css 已映射 --ke-*,不写裸色值) */
.chip-dot { width: 6px; height: 6px; border-radius: var(--ke-radius-full); }
.dot-success { background: var(--el-color-success); }
.dot-warning { background: var(--el-color-warning); }
.dot-info { background: var(--el-color-info); }
.dot-danger { background: var(--el-color-danger); }
.search { width: 220px; }
.load-error { margin: 0; padding: 8px 12px; border-radius: var(--ke-radius-s); background: var(--ke-danger-soft); color: var(--ke-danger); font-size: 13px; }
.tpl-chip { font-size: 11px; padding: 3px 6px; border-radius: var(--ke-radius-xs); background: var(--ke-surface-2); color: var(--ke-sub); font-weight: 400; }
.num { font-variant-numeric: tabular-nums; }
.page-foot { display: flex; justify-content: flex-end; }
</style>
