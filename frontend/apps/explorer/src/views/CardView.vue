<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showToast } from 'vant';
import { KeIcon, SourceList } from '@ke/shared';
import { ApiError } from '../api/http';
import { fetchCardEntries, getCard } from '../api/cards';
import type { CardDetail, CardEntryItem, CardEntryGroup, TextContent } from '../api/cards';
import { favorite, unfavorite } from '../api/favorites';
import CitationPopover from '../components/CitationPopover.vue';
import { CARD_TYPE_LABELS, CardRenderer } from '../components/CardRenderer';
import ServiceBar from '../components/ServiceBar.vue';
import { THEME_NAMES } from '../mock/home';
import { useAuthStore } from '../stores/auth';

// 卡片页(04 §7.2 KCard):chips 行 → 宋体标题 → 摘要(虚线分隔)→ 插图占位 →
// 正文(CardRenderer 按 templateType 分发)→ SourceList → 探索入口;底部 ServiceBar 常驻
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const card = ref<CardDetail | null>(null);
const entries = ref<CardEntryGroup | null>(null);
const entriesError = ref(false);
const foldOpen = ref(false);
const notFound = ref(false);
const errorMsg = ref('');
const loading = ref(true);
/** 收藏态(FR-C10):详情 favorited 带出初态,点击调 API 切换 */
const favorited = ref(false);
const favBusy = ref(false);
/** 出处联动状态(P3-15):当前点亮的角标序号(2s 回落),null=无 */
const cited = ref<number | null>(null);
let citeTimer: number | undefined;

/** 详情加载时序守卫:新请求发起后,旧请求的慢响应一律丢弃 */
let cardSeq = 0;

/** 入口独立加载:失败只降级入口区(局部重试),不遮蔽卡主内容;seq 守卫丢弃切卡后的旧响应 */
let entrySeq = 0;

async function loadEntries(id: number): Promise<void> {
  const seq = ++entrySeq;
  if (!auth.token) return;
  entriesError.value = false;
  entries.value = null;
  try {
    // 入口接口要 viewer 身份(公有/私有过滤):匿名不调用,给登录引导
    const group = await fetchCardEntries(id);
    if (seq !== entrySeq) return; // 过期响应:已切到别的卡,丢弃
    entries.value = group;
  } catch {
    if (seq !== entrySeq) return;
    entriesError.value = true;
  }
}

async function load(): Promise<void> {
  // 时序守卫(同 CardsView reqSeq):相关跳转复用本组件,快速切卡后旧慢响应一律丢弃
  const seq = ++cardSeq;
  loading.value = true;
  notFound.value = false;
  errorMsg.value = '';
  card.value = null;
  favorited.value = false;
  favBusy.value = false;
  cited.value = null;
  window.clearTimeout(citeTimer);
  entries.value = null;
  entriesError.value = false;
  foldOpen.value = false;
  const id = Number(route.params.id);
  if (!Number.isInteger(id) || id <= 0) {
    notFound.value = true;
    loading.value = false;
    return;
  }
  try {
    const detail = await getCard(id);
    if (seq !== cardSeq) return; // 过期响应:已切到别的卡,丢弃
    // 不存在/非 PUBLISHED 一律 404(后端契约)→ 空态,不泄露草稿存在性
    card.value = detail;
    notFound.value = detail === null;
    favorited.value = detail?.favorited ?? false;
  } catch (e) {
    if (seq !== cardSeq) return;
    errorMsg.value = e instanceof Error ? e.message : '加载失败,请稍后重试';
  } finally {
    if (seq === cardSeq) {
      loading.value = false;
    }
  }
  if (card.value !== null) void loadEntries(id);
}

/** 入口区局部重试 */
function retryEntries(): void {
  const id = Number(route.params.id);
  if (Number.isInteger(id) && id > 0) void loadEntries(id);
}

void load();

// 相关卡跳转复用本组件,route 参数变化时重拉
watch(
  () => route.params.id,
  () => {
    if (route.params.id !== undefined) void load();
  }
);

const typeLabel = computed(() => CARD_TYPE_LABELS[card.value?.templateType ?? ''] ?? '卡片');
const themeName = computed(() => {
  const theme = card.value?.theme;
  return theme ? THEME_NAMES[theme] ?? theme : '';
});

/** 仅图文卡有摘要段(其他卡型的说明由各自渲染器承载) */
const textSummary = computed(() => {
  if (card.value?.templateType !== 'TEXT') return '';
  return (card.value.content as TextContent).summary ?? '';
});

const sourceRows = computed(() =>
  (card.value?.sources ?? []).map((s, i) => ({
    index: i + 1,
    title: s.title,
    locator: s.locator,
    license: s.license
  }))
);

// —— 出处联动(P3-15):点角标 → 清单对应行高亮(2s 回落)+ CitationPopover 浮出对应出处 ——
const srcWrap = ref<HTMLElement | null>(null);
const HIGHLIGHT_MS = 2000;

const citedSource = computed(() => sourceRows.value.find((s) => s.index === cited.value) ?? null);

/** 角标点击:高亮出处清单对应行(scrollIntoView + 放大提示),浮层随高亮窗口显隐 */
function onCite(n: number): void {
  cited.value = n;
  window.clearTimeout(citeTimer);
  citeTimer = window.setTimeout(() => {
    cited.value = null;
  }, HIGHLIGHT_MS);
  void nextTick(() => {
    srcWrap.value?.querySelector('.row-hl')?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  });
}

// —— 收藏(FR-C10):匿名引导登录并带 redirect 回跳(登录后回到本卡);已登录调 API 切换 ——
async function toggleFavorite(): Promise<void> {
  if (!auth.token) {
    void router.push({ path: '/login', query: { redirect: route.fullPath } });
    return;
  }
  if (favBusy.value || !card.value) return;
  favBusy.value = true;
  try {
    const next = !favorited.value;
    const resp = await (next ? favorite(card.value.id) : unfavorite(card.value.id));
    favorited.value = resp.favorited;
  } catch (e) {
    showToast(e instanceof ApiError ? e.message : '操作失败,请稍后重试');
  } finally {
    favBusy.value = false;
  }
}

const visibleEntries = computed(() => {
  if (!entries.value) return [];
  return foldOpen.value
    ? [...entries.value.defaultEntries, ...entries.value.folded]
    : entries.value.defaultEntries;
});

/** 返回:有历史则 back,直达链接兜底回首页 */
function goBack(): void {
  if (window.history.state?.back == null) {
    void router.replace('/home');
  } else {
    router.back();
  }
}

function openCard(cardId: number): void {
  void router.push(`/cards/${cardId}`);
}

/** 入口点击:链接入口跳目标卡;服务/比较入口由 Phase 5 接线 */
function onEntry(e: CardEntryItem): void {
  if (e.type === 'LINK_CARD' && e.targetCardId != null) {
    openCard(e.targetCardId);
    return;
  }
  console.info(`入口「${e.name}」由 Phase 5 服务接线`);
}

function onService(name: string): void {
  console.info(`服务「${name}」由 Phase 5 接线`);
}

/** 入口类型 → 图标(与首页 SVG 线性图标语言一致) */
function entryIcon(type: string): string {
  if (type === 'LINK_CARD') return 'path';
  if (type === 'COMPARE') return 'scale';
  if (type === 'AGENT_SERVICE') return 'compass';
  return 'star';
}

function entrySub(e: CardEntryItem): string {
  if (e.type === 'LINK_CARD') return e.relationLabel ?? '相关联';
  if (e.type === 'COMPARE') return '比较服务';
  if (e.type === 'AGENT_SERVICE') {
    return e.serviceType ? `智能体服务 · ${e.serviceType}` : '智能体服务';
  }
  return e.relationLabel ?? '深入了解';
}
</script>

<template>
  <div class="page">
    <header class="topbar">
      <button
        type="button"
        class="ic"
        aria-label="返回"
        @click="goBack"
      >
        <KeIcon name="back" />
      </button>
      <div class="crumb">
        <template v-if="card">
          {{ themeName }} · <b>{{ card.title }}</b>
        </template>
        <template v-else>
          卡片详情
        </template>
      </div>
      <button
        type="button"
        class="ic fav-btn"
        :class="{ faved: favorited }"
        :aria-label="favorited ? '取消收藏' : '收藏'"
        :disabled="favBusy"
        @click="toggleFavorite"
      >
        <KeIcon name="star" />
      </button>
      <button
        type="button"
        class="ic"
        aria-label="我的路径"
      >
        <KeIcon name="compass" />
      </button>
    </header>

    <p
      v-if="loading"
      class="state"
    >
      加载中…
    </p>

    <div
      v-else-if="notFound"
      class="empty"
    >
      <KeIcon
        class="empty-ic"
        name="alert"
      />
      <b class="empty-t">
        卡片不存在或已下架
      </b>
      <span class="empty-s">
        它可能还未发布,或已被移除
      </span>
      <button
        type="button"
        class="empty-btn"
        @click="router.push('/home')"
      >
        回首页看看
      </button>
    </div>

    <div
      v-else-if="errorMsg"
      class="empty"
    >
      <KeIcon
        class="empty-ic"
        name="alert"
      />
      <b class="empty-t">
        加载没有成功
      </b>
      <span class="empty-s">
        {{ errorMsg }}
      </span>
      <button
        type="button"
        class="empty-btn"
        @click="load"
      >
        重试
      </button>
    </div>

    <main
      v-else-if="card"
      class="scroll"
    >
      <article class="kcard">
        <div class="chips">
          <span class="chip">
            {{ typeLabel }}
          </span>
          <span
            v-if="themeName"
            class="chip plain"
          >
            {{ themeName }}
          </span>
        </div>
        <h1 class="kt">
          {{ card.title }}
        </h1>
        <p
          v-if="textSummary"
          class="ksum"
        >
          {{ textSummary }}
        </p>
        <div
          v-if="card.templateType === 'TEXT'"
          class="kimg"
        >
          <KeIcon
            class="kimg-ic"
            name="temple"
          />
          <span class="kimg-tag">
            示意图
          </span>
        </div>
        <CardRenderer
          :template-type="card.templateType"
          :content="card.content as object"
          @cite="onCite"
          @open="openCard"
        />
        <div
          ref="srcWrap"
          class="srclist"
        >
          <CitationPopover
            v-if="citedSource"
            class="cite-pop"
            :source="citedSource"
            :index="citedSource.index"
          />
          <SourceList
            :sources="sourceRows"
            :highlight-index="cited"
          />
        </div>
      </article>

      <section class="sec">
        <div class="sec-title">
          探索入口 <span class="ln" />
        </div>
        <template v-if="auth.token">
          <button
            v-if="entriesError"
            type="button"
            class="retry-entry"
            @click="retryEntries"
          >
            入口加载失败,点击重试
          </button>
          <template v-else-if="entries">
            <button
              v-for="e in visibleEntries"
              :key="e.id"
              type="button"
              class="entry"
              @click="onEntry(e)"
            >
              <span class="ei">
                <KeIcon :name="entryIcon(e.type)" />
              </span>
              <span class="et">
                <span class="en">
                  {{ e.name }}
                </span>
                <span class="er">
                  {{ entrySub(e) }}
                </span>
              </span>
              <KeIcon
                class="ea"
                name="chev"
              />
            </button>
            <button
              v-if="entries.folded.length && !foldOpen"
              type="button"
              class="fold"
              @click="foldOpen = true"
            >
              还有 {{ entries.folded.length }} 个入口，展开 ∨
            </button>
          </template>
        </template>
        <button
          v-else
          type="button"
          class="fold"
          @click="router.push({ path: '/login', query: { redirect: route.fullPath } })"
        >
          登录后查看这张卡的探索入口
        </button>
        <button
          type="button"
          class="addentry"
        >
          <KeIcon
            class="add-ic"
            name="plus"
          />
          用一句话新增入口
        </button>
      </section>
    </main>

    <ServiceBar
      v-if="card"
      @explain="onService('讲清楚')"
      @compare="onService('帮我比较')"
      @organize="onService('整理发现')"
    />
  </div>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; padding: 52px 16px 96px; background: var(--ke-bg); }
.topbar { position: fixed; top: 0; left: 0; right: 0; z-index: var(--ke-z-bar); display: flex; align-items: center; gap: 8px; height: 48px; padding: 0 10px; background: color-mix(in srgb, var(--ke-bg) 86%, transparent); backdrop-filter: blur(8px); border-bottom: 1px solid var(--ke-line-2); }
.ic { display: flex; width: 34px; height: 34px; align-items: center; justify-content: center; border: none; border-radius: var(--ke-radius-full); background: transparent; color: var(--ke-ink); cursor: pointer; }
.ic:active { background: var(--ke-primary-soft); }
.crumb { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--ke-sub); }
.crumb b { color: var(--ke-ink); font-weight: 700; }
.state { margin: 40px 0 0; text-align: center; font-size: 12px; color: var(--ke-sub); }
.kcard { margin: 4px 0 12px; padding: 16px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { display: inline-block; padding: 1px 9px; border-radius: var(--ke-radius-full); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 11px; font-weight: 600; line-height: 1.7; }
.chip.plain { background: var(--ke-line-2); color: var(--ke-sub); }
.kt { margin: 10px 0 0; font-family: var(--ke-font-display); font-size: 19px; font-weight: 900; line-height: 1.4; color: var(--ke-ink); }
.ksum { margin: 8px 0 0; padding-bottom: 10px; border-bottom: 1px dashed var(--ke-line); font-size: 13px; line-height: 1.75; color: var(--ke-sub); }
.kimg { position: relative; display: flex; align-items: center; justify-content: center; height: 124px; margin-top: 12px; border-radius: var(--ke-radius-m); background: var(--ke-surface-2); color: var(--ke-sub-2); }
.kimg-ic { width: 34px; height: 34px; }
.kimg-tag { position: absolute; right: 8px; bottom: 8px; padding: 1px 8px; border-radius: var(--ke-radius-s); background: var(--ke-surface); color: var(--ke-sub-2); font-size: 10px; }
.cr-fallback { margin: 12px 0 0; font-size: 13px; color: var(--ke-sub); }
.srclist { margin-top: 12px; }
.srclist .cite-pop { margin: 0 0 8px; }
.fav-btn.faved { color: var(--ke-primary); }
.fav-btn.faved .ke-icon { fill: var(--ke-primary); }
.ic:disabled { opacity: 0.5; cursor: default; }
.sec-title { display: flex; align-items: center; gap: 6px; margin: 14px 0 8px; font-size: 13px; font-weight: 700; color: var(--ke-ink); }
.sec-title .ln { flex: 1; height: 1px; background: var(--ke-line); }
.entry { display: flex; width: 100%; align-items: center; gap: 10px; margin: 0 0 8px; padding: 12px 13px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); text-align: left; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); box-sizing: border-box; }
.entry:active { background: var(--ke-primary-soft); }
.ei { display: flex; width: 36px; height: 36px; flex-shrink: 0; align-items: center; justify-content: center; border-radius: var(--ke-radius-s); background: var(--ke-primary-soft); color: var(--ke-primary); }
.et { flex: 1; min-width: 0; }
.en { display: block; font-size: 14px; font-weight: 600; line-height: 1.5; color: var(--ke-ink); }
.er { display: block; margin-top: 2px; font-size: 12px; line-height: 1.5; color: var(--ke-sub); }
.ea { flex-shrink: 0; color: var(--ke-sub-2); }
.fold, .retry-entry { display: block; width: 100%; margin: 8px 0 0; padding: 10px; border: none; border-radius: var(--ke-radius-l); background: transparent; font-size: 12px; font-weight: 600; font-family: var(--ke-font); color: var(--ke-sub); text-align: center; cursor: pointer; box-sizing: border-box; }
.addentry { display: flex; width: 100%; align-items: center; justify-content: center; gap: 5px; margin-top: 10px; padding: 11px; border: 1.5px dashed var(--ke-line-strong); border-radius: var(--ke-radius-l); background: var(--ke-surface-2); color: var(--ke-primary); font-size: 13px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; box-sizing: border-box; }
.add-ic { width: 14px; height: 14px; }
.empty { margin: 60px auto 0; max-width: 320px; text-align: center; }
.empty-ic { width: 40px; height: 40px; color: var(--ke-sub-2); }
.empty-t { display: block; margin-top: 10px; font-size: 14px; font-weight: 600; color: var(--ke-ink); }
.empty-s { display: block; margin-top: 4px; font-size: 12px; color: var(--ke-sub); }
.empty-btn { display: inline-block; margin-top: 14px; padding: 8px 18px; border: none; border-radius: var(--ke-radius-m); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 13px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; }
</style>
