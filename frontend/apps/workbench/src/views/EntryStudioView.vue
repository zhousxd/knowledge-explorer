<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ApiError } from '../api/http';
import { fetchMyEntries } from '../api/entries';
import type { WorkbenchEntry } from '../api/entries';

/**
 * 入口编排工作台(FR-N04/N05 界面,Task 28 + P6-28 前置审核语义):当前用户创建的全部入口一览——
 * 状态 tabs(全部/生效/已停用;PENDING 待审入口在「全部」可见并标「待审核」,裁决走审核中心)+
 * 入口表(名称/类型/所属卡/范围/状态/试运行次数)+ 详情抽屉简版(全字段只读)。
 * 入口的创建/试运行/送审在探索端四步流完成;PUBLIC 入口 PENDING 态对他人不可见,approve 后生效。
 */
type StatusTab = 'ALL' | 'ACTIVE' | 'DISABLED';

const TABS: Array<{ key: StatusTab; label: string }> = [
  { key: 'ALL', label: '全部' },
  { key: 'ACTIVE', label: '生效中' },
  { key: 'DISABLED', label: '已停用' }
];

const entries = ref<WorkbenchEntry[]>([]);
const loading = ref(false);
const errorMsg = ref('');
const activeTab = ref<StatusTab>('ALL');

const filtered = computed(() =>
  activeTab.value === 'ALL' ? entries.value : entries.value.filter((e) => e.status === activeTab.value)
);

const counts = computed<Record<StatusTab, number>>(() => ({
  ALL: entries.value.length,
  ACTIVE: entries.value.filter((e) => e.status === 'ACTIVE').length,
  DISABLED: entries.value.filter((e) => e.status === 'DISABLED').length
}));

async function load(): Promise<void> {
  loading.value = true;
  errorMsg.value = '';
  try {
    entries.value = await fetchMyEntries();
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '加载失败,请稍后重试';
  } finally {
    loading.value = false;
  }
}

function switchTab(tab: StatusTab): void {
  if (activeTab.value === tab) return;
  activeTab.value = tab;
}

// ---------- 详情抽屉(简版,只读) ----------

const detail = ref<WorkbenchEntry | null>(null);

function openDetail(entry: WorkbenchEntry): void {
  detail.value = entry;
}

function closeDetail(): void {
  detail.value = null;
}

const TYPE_LABELS: Record<string, string> = {
  AGENT_SERVICE: '智能体服务',
  COMPARE: '比较入口',
  LINK_CARD: '链接入口'
};

function typeLabel(entry: WorkbenchEntry): string {
  const base = TYPE_LABELS[entry.type] ?? entry.type;
  return entry.serviceType ? `${base} · ${entry.serviceType}` : base;
}

function relationText(entry: WorkbenchEntry): string {
  return entry.relationLabel ?? '—';
}

function scopeText(entry: WorkbenchEntry): string {
  return entry.scope === 'PUBLIC' ? '公共区' : '个人空间';
}

/** 状态文案(P6-28 前置审核):PENDING=待审核(approve 后对他人可见),走审核中心裁决 */
function statusText(entry: WorkbenchEntry): string {
  if (entry.status === 'PENDING') return '待审核';
  return entry.status === 'ACTIVE' ? '生效中' : '已停用';
}

function statusChipClass(entry: WorkbenchEntry): string {
  if (entry.status === 'PENDING') return 'chip-pending';
  return entry.status === 'ACTIVE' ? 'chip-active' : 'chip-off';
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="page">
    <h2 class="page-title">
      入口编排
    </h2>
    <p class="page-sub">
      入口的创建、试运行与送审在探索端卡片页「用一句话新增入口」完成;待审核的公共入口请到
      <router-link
        class="link"
        to="/reviews"
      >
        审核中心
      </router-link>处理
    </p>

    <p
      v-if="errorMsg"
      class="load-error"
      role="alert"
    >
      {{ errorMsg }}
    </p>

    <div
      class="tabs"
      role="tablist"
      aria-label="入口状态筛选"
    >
      <button
        v-for="tab in TABS"
        :key="tab.key"
        class="tab"
        :class="{ 'is-active': activeTab === tab.key }"
        type="button"
        role="tab"
        :aria-selected="activeTab === tab.key"
        :data-tab="tab.key"
        @click="switchTab(tab.key)"
      >
        {{ tab.label }}({{ counts[tab.key] }})
      </button>
    </div>

    <div
      v-loading="loading"
      class="table-wrap"
    >
      <table
        v-if="filtered.length"
        class="entry-table"
      >
        <thead>
          <tr>
            <th>名称</th>
            <th>类型</th>
            <th>所属卡片</th>
            <th>范围</th>
            <th>状态</th>
            <th class="num">
              试运行次数
            </th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="entry in filtered"
            :key="entry.id"
          >
            <td class="name">
              {{ entry.name }}
            </td>
            <td>{{ typeLabel(entry) }}</td>
            <td>{{ entry.cardTitle || '—' }}</td>
            <td>
              <span
                class="chip"
                :class="entry.scope === 'PUBLIC' ? 'chip-public' : 'chip-private'"
              >{{ scopeText(entry) }}</span>
            </td>
            <td>
              <span
                class="chip"
                :class="statusChipClass(entry)"
              >{{ statusText(entry) }}</span>
            </td>
            <td class="num">
              {{ entry.testTotal }}
            </td>
            <td>
              <el-button
                class="detail-btn"
                size="small"
                text
                type="primary"
                @click="openDetail(entry)"
              >
                详情
              </el-button>
            </td>
          </tr>
        </tbody>
      </table>
      <p
        v-else-if="!loading"
        class="empty"
      >
        还没有入口,去探索端卡片页用一句话创建一个吧
      </p>
    </div>

    <el-drawer
      :model-value="detail !== null"
      title="入口详情"
      size="360px"
      @close="closeDetail"
    >
      <dl
        v-if="detail"
        class="detail"
      >
        <dt>名称</dt>
        <dd>{{ detail.name }}</dd>
        <dt>类型</dt>
        <dd>{{ typeLabel(detail) }}</dd>
        <dt>所属卡片</dt>
        <dd>{{ detail.cardTitle || '—' }}(id:{{ detail.cardId }})</dd>
        <dt>目标卡片</dt>
        <dd>{{ detail.targetCardId ?? '—' }}</dd>
        <dt>关系词</dt>
        <dd>{{ relationText(detail) }}</dd>
        <dt>范围</dt>
        <dd>{{ scopeText(detail) }}</dd>
        <dt>状态</dt>
        <dd>{{ detail ? statusText(detail) : '' }}</dd>
        <dt>试运行次数</dt>
        <dd>{{ detail.testTotal }}</dd>
        <dt>入口 ID</dt>
        <dd>{{ detail.id }}</dd>
      </dl>
    </el-drawer>
  </section>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 12px; }
.page-title { margin: 0; color: var(--ke-ink); font-size: 18px; }
.page-sub { margin: 0; color: var(--ke-sub); font-size: 12px; }
.link { color: var(--ke-primary); }

.load-error { margin: 0; padding: 8px 12px; border-radius: var(--ke-radius-s); background: var(--ke-danger-soft); color: var(--ke-danger); font-size: 13px; }

.tabs { display: flex; gap: 8px; }
.tab { height: 28px; padding: 0 14px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-full); background: var(--ke-surface); color: var(--ke-sub); font-size: 12px; cursor: pointer; transition: border-color var(--ke-dur-fast) var(--ke-ease), color var(--ke-dur-fast) var(--ke-ease); }
.tab:hover { border-color: var(--ke-primary); color: var(--ke-primary); }
.tab.is-active { border-color: var(--ke-primary); background: var(--ke-primary); color: var(--ke-white); }

.table-wrap { min-height: 120px; }
.entry-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.entry-table th { padding: 8px 10px; border-bottom: 1px solid var(--ke-line-strong); color: var(--ke-sub); font-size: 12px; font-weight: 600; text-align: left; }
.entry-table td { padding: 10px; border-bottom: 1px solid var(--ke-line); color: var(--ke-ink-2); }
.entry-table .num { text-align: right; }
.entry-table .name { color: var(--ke-ink); font-weight: 600; }
.chip { display: inline-block; padding: 2px 10px; border-radius: var(--ke-radius-full); font-size: 11px; font-weight: 600; }
.chip-public { background: var(--ke-primary-soft); color: var(--ke-primary); }
.chip-private { background: var(--ke-private-soft); color: var(--ke-private); }
.chip-active { background: var(--ke-success-soft); color: var(--ke-success); }
.chip-pending { background: var(--ke-warn-soft); color: var(--ke-warn); }
.chip-off { background: var(--ke-bg); color: var(--ke-sub); }

.empty { margin: 0; padding: 40px 0; text-align: center; color: var(--ke-sub); font-size: 13px; }

.detail { margin: 0; display: grid; grid-template-columns: 84px 1fr; row-gap: 10px; font-size: 13px; }
.detail dt { color: var(--ke-sub); }
.detail dd { margin: 0; color: var(--ke-ink-2); word-break: break-all; }
</style>
