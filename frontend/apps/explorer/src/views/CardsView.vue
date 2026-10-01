<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { KeIcon } from '@ke/shared';
import { listPublicCards } from '../api/cards';
import type { PublicCardListItem } from '../api/cards';
import { CARD_TYPE_LABELS } from '../components/CardRenderer';
import { HOME_THEMES, THEME_NAMES } from '../mock/home';

// 卡片列表页(/cards):搜索框回填 q + 专题 tab + 卡片行(标题宋体 + 模板 chip + 摘要 2 行截断)
// keyset 分页用「加载更多」按钮(移动端免滚动模拟),无 nextCursor 显示「没有更多了」尾标
const route = useRoute();
const router = useRouter();

/** 专题 tab(全部 + 首页三专题,key 即 /cards?theme= 过滤值) */
const THEME_TABS = [
  { label: '全部', value: '' },
  ...HOME_THEMES.map((t) => ({ label: t.name, value: t.key }))
];

const keyword = ref(String(route.query.q ?? ''));
const activeTheme = ref(String(route.query.theme ?? ''));
const rows = ref<PublicCardListItem[]>([]);
const cursor = ref<string | null>(null);
const loading = ref(true);
const loadingMore = ref(false);
const errorMsg = ref('');

async function fetchPage(nextCursor: string | null, append: boolean): Promise<void> {
  if (append) {
    loadingMore.value = true;
  } else {
    loading.value = true;
  }
  errorMsg.value = '';
  try {
    const page = await listPublicCards({
      theme: activeTheme.value || undefined,
      q: keyword.value.trim() || undefined,
      cursor: nextCursor ?? undefined
    });
    rows.value = append ? [...rows.value, ...page.items] : page.items;
    cursor.value = page.nextCursor;
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载失败,请稍后重试';
  } finally {
    loading.value = false;
    loadingMore.value = false;
  }
}

/** 首页/搜索/tab 变更后重置游标拉首页 */
function reload(): void {
  void fetchPage(null, false);
}

onMounted(reload);

// 搜索/专题切换走 router.replace,query 变化统一在此重拉
watch(
  () => [route.query.q, route.query.theme],
  () => {
    keyword.value = typeof route.query.q === 'string' ? route.query.q : '';
    activeTheme.value = typeof route.query.theme === 'string' ? route.query.theme : '';
    reload();
  }
);

function cleanQuery(q: string, theme: string): Record<string, string> {
  const query: Record<string, string> = {};
  if (q) query.q = q;
  if (theme) query.theme = theme;
  return query;
}

function onSearch(): void {
  void router.replace({ query: cleanQuery(keyword.value.trim(), activeTheme.value) });
}

function onTheme(value: string): void {
  if (value === activeTheme.value) return;
  void router.replace({ query: cleanQuery(keyword.value.trim(), value) });
}

function goCard(id: number): void {
  void router.push(`/cards/${id}`);
}

function typeLabel(type: string): string {
  return CARD_TYPE_LABELS[type] ?? '卡片';
}
</script>

<template>
  <div class="page">
    <header class="head">
      <h1 class="title">
        卡片
      </h1>
      <form
        class="search"
        role="search"
        @submit.prevent="onSearch"
      >
        <KeIcon
          class="s-icon"
          name="search"
        />
        <input
          v-model="keyword"
          class="s-input"
          type="search"
          name="q"
          placeholder="搜索卡片、主题或问题"
          aria-label="搜索卡片、主题或问题"
        >
      </form>
      <nav
        class="tabs"
        aria-label="专题筛选"
      >
        <button
          v-for="t in THEME_TABS"
          :key="t.value"
          type="button"
          class="tab"
          :class="{ on: t.value === activeTheme }"
          @click="onTheme(t.value)"
        >
          {{ t.label }}
        </button>
      </nav>
    </header>

    <p
      v-if="errorMsg"
      class="state"
    >
      {{ errorMsg }}
    </p>
    <p
      v-else-if="loading"
      class="state"
    >
      加载中…
    </p>
    <div
      v-else
      class="list"
    >
      <button
        v-for="row in rows"
        :key="row.id"
        type="button"
        class="cardrow"
        @click="goCard(row.id)"
      >
        <span class="row-head">
          <span class="chip">
            {{ typeLabel(row.templateType) }}
          </span>
          <span class="theme-tag">
            {{ THEME_NAMES[row.theme] ?? row.theme }}
          </span>
        </span>
        <b class="row-title">
          {{ row.title }}
        </b>
        <span
          v-if="row.summaryText"
          class="row-sum"
        >
          {{ row.summaryText }}
        </span>
      </button>

      <div
        v-if="!rows.length"
        class="empty"
      >
        <KeIcon
          class="empty-ic"
          name="search"
        />
        <b class="empty-t">
          没有找到相关卡片
        </b>
        <span class="empty-s">
          换个关键词试试
        </span>
      </div>
      <button
        v-else-if="cursor"
        type="button"
        class="more"
        :disabled="loadingMore"
        @click="cursor && fetchPage(cursor, true)"
      >
        {{ loadingMore ? '加载中…' : '加载更多' }}
      </button>
      <p
        v-else
        class="end"
      >
        没有更多了
      </p>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; padding: 24px 16px 40px; background: var(--ke-bg); }
.title { margin: 0; font-family: var(--ke-font-display); font-size: 20px; font-weight: 900; line-height: 1.3; color: var(--ke-ink); }
.search { display: flex; align-items: center; gap: 8px; height: 44px; margin-top: 12px; padding: 0 14px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-l); background: var(--ke-surface); transition: border-color var(--ke-dur-fast) var(--ke-ease), box-shadow var(--ke-dur-fast) var(--ke-ease); }
.search:focus-within { border-color: var(--ke-primary); box-shadow: var(--ke-focus); }
.s-icon { width: 18px; height: 18px; color: var(--ke-sub-2); }
.s-input { flex: 1; min-width: 0; height: 100%; border: none; outline: none; background: transparent; color: var(--ke-ink); font-size: 14px; }
.s-input::placeholder { color: var(--ke-sub-2); }
.tabs { display: flex; gap: 8px; margin-top: 12px; overflow-x: auto; }
.tab { flex-shrink: 0; padding: 6px 14px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-full); background: var(--ke-surface); font-size: 12.5px; font-weight: 600; font-family: var(--ke-font); color: var(--ke-sub); cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); }
.tab.on { border-color: var(--ke-primary); background: var(--ke-primary-soft); color: var(--ke-primary); }
.state { margin: 40px 0 0; text-align: center; font-size: 12px; color: var(--ke-sub); }
.cardrow { display: block; width: 100%; margin: 0 0 10px; padding: 13px 14px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); text-align: left; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); box-sizing: border-box; }
.cardrow:active { background: var(--ke-primary-soft); }
.row-head { display: flex; align-items: center; gap: 6px; }
.chip { display: inline-block; padding: 0 8px; border-radius: var(--ke-radius-full); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 11px; font-weight: 600; line-height: 1.8; }
.theme-tag { font-size: 11px; color: var(--ke-sub-2); }
.row-title { display: block; margin-top: 7px; font-family: var(--ke-font-display); font-size: 15px; font-weight: 700; line-height: 1.45; color: var(--ke-ink); }
.row-sum { display: -webkit-box; margin-top: 4px; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; font-size: 12.5px; line-height: 1.6; color: var(--ke-sub); }
.empty { margin: 60px auto 0; max-width: 320px; text-align: center; }
.empty-ic { width: 40px; height: 40px; color: var(--ke-sub-2); }
.empty-t { display: block; margin-top: 10px; font-size: 14px; font-weight: 600; color: var(--ke-ink); }
.empty-s { display: block; margin-top: 4px; font-size: 12px; color: var(--ke-sub); }
.more { display: block; width: 100%; margin: 14px 0 0; padding: 11px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-l); background: var(--ke-surface); font-size: 13px; font-weight: 700; font-family: var(--ke-font); color: var(--ke-ink-2); cursor: pointer; box-sizing: border-box; }
.more:disabled { opacity: 0.45; cursor: default; }
.end { margin: 14px 0 0; text-align: center; font-size: 11px; color: var(--ke-sub-2); }
</style>
