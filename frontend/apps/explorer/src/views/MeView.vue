<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { KeIcon } from '@ke/shared';
import { listFavorites } from '../api/favorites';
import type { FavoriteItem } from '../api/favorites';
import { fetchMyEntries } from '../api/entries';
import type { MineEntryItem } from '../api/entries';
import { fetchMySessions } from '../api/sessions';
import { fetchMyQuota } from '../api/me';
import type { QuotaView } from '../api/me';

/**
 * 个人空间(FR-U02/U05,Task 34):四入口卡 + 今日配额区。
 * 四入口:我的收藏(展开收藏行,点击回卡片详情)/我的路径(跳 /path)/
 * 路径与成果(整理在路径内,跳 /path——/summary 需带会话上下文,无会话清单端点)/
 * 私人入口(展开入口行,名称/状态 chip)。计数:收藏 total / 路径总数(sessions?size=1 取
 * total)/入口数(/entries/mine 长度)。配额区(04 §8.6):进度条主色,用尽变警示 +
 * 恢复时间(次日零点,resetAt 直出)。
 */
const router = useRouter();

// ---------- 计数与清单(各源失败互不影响,失败卡计数显示 —) ----------

const favTotal = ref<number | null>(null);
const favs = ref<FavoriteItem[]>([]);
const sessionTotal = ref<number | null>(null);
const entryTotal = ref<number | null>(null);
const entries = ref<MineEntryItem[]>([]);

onMounted(() => {
  void listFavorites(1, 20)
    .then((page) => {
      favTotal.value = page.total;
      favs.value = page.items;
    })
    .catch(() => {});
  // 路径总数:P8 决策——无专门计数端点,sessions 列表 size=1 只取 total
  void fetchMySessions(1, 1)
    .then((page) => {
      sessionTotal.value = page.total;
    })
    .catch(() => {});
  void fetchMyEntries()
    .then((list) => {
      entries.value = list;
      entryTotal.value = list.length;
    })
    .catch(() => {});
  void loadQuota();
});

// ---------- 配额(FR-U05,04 §8.6) ----------

const quota = ref<QuotaView | null>(null);
const quotaError = ref(false);

async function loadQuota(): Promise<void> {
  quotaError.value = false;
  try {
    quota.value = await fetchMyQuota();
  } catch {
    quotaError.value = true;
  }
}

const quotaFull = computed(() => quota.value !== null && quota.value.used >= quota.value.limit);

/** 进度条填充宽度(已用/上限,封顶 100%) */
const quotaPercent = computed(() => {
  if (!quota.value || quota.value.limit <= 0) return 0;
  return Math.min(100, (quota.value.used / quota.value.limit) * 100);
});

/**
 * resetAt → 「M月D日 HH:mm」。直接从 ISO 串截取本地时字段(后端序列化即本地时区零点),
 * 不经 Date 转换,避免跨时区机器漂移。
 */
function resetTimeCn(iso: string): string {
  const m = /(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(iso);
  if (!m) return '';
  return `${Number(m[2])}月${Number(m[3])}日 ${m[4]}:${m[5]}`;
}

// ---------- 收藏/入口列表展开 ----------

const showFavs = ref(false);
const showEntries = ref(false);

/** 入口状态 → chip 文案(ACTIVE 私有直用/PUBLIC 送审 PENDING/下架 DISABLED) */
const ENTRY_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '可用',
  PENDING: '审核中',
  DISABLED: '已停用'
};

function goCard(cardId: number): void {
  void router.push(`/cards/${cardId}`);
}

function goPath(): void {
  void router.push('/path');
}
</script>

<template>
  <div class="page">
    <h1 class="title">
      个人空间
    </h1>

    <!-- 配额区(FR-U05):进度条主色,用尽变警示(04 §8.6) -->
    <section
      class="quota-card"
      data-testid="quota"
    >
      <template v-if="quota">
        <header class="q-head">
          <span class="q-label">今日智能服务配额</span>
          <span
            class="q-num"
            :class="{ 'is-full': quotaFull }"
          >{{ quota.used }}/{{ quota.limit }}</span>
        </header>
        <div
          class="q-bar"
          role="progressbar"
          :aria-valuenow="quota.used"
          aria-valuemin="0"
          :aria-valuemax="quota.limit"
        >
          <div
            class="q-fill"
            :class="{ 'is-full': quotaFull }"
            :style="{ width: `${quotaPercent}%` }"
            data-testid="quota-fill"
          />
        </div>
        <p
          class="q-hint"
          :class="{ 'is-full': quotaFull }"
          data-testid="quota-hint"
        >
          <template v-if="quotaFull">
            今日 {{ quota.limit }} 次智能服务已用完,{{ resetTimeCn(quota.resetAt) }} 恢复
          </template>
          <template v-else>
            剩余 {{ quota.remaining }} 次 · {{ resetTimeCn(quota.resetAt) }} 重置
          </template>
        </p>
      </template>
      <p
        v-else-if="quotaError"
        class="q-hint"
        role="alert"
      >
        配额加载失败,请稍后重试
      </p>
    </section>

    <!-- 四入口卡(FR-U02) -->
    <section class="entries">
      <article class="me-card">
        <button
          type="button"
          class="card-head"
          data-testid="fav-toggle"
          :aria-expanded="showFavs"
          @click="showFavs = !showFavs"
        >
          <span class="c-icon"><KeIcon name="star" /></span>
          <span class="c-text">
            <span class="c-name">我的收藏</span>
            <span class="c-sub">回看的卡片都会收在这里</span>
          </span>
          <span class="c-count">{{ favTotal ?? '—' }}</span>
          <span
            class="c-arrow"
            :class="{ open: showFavs }"
          ><KeIcon name="chev" /></span>
        </button>
        <ul v-if="showFavs">
          <li
            v-for="fav in favs"
            :key="fav.cardId"
          >
            <button
              type="button"
              class="row"
              :data-card-id="fav.cardId"
              @click="goCard(fav.cardId)"
            >
              <span class="r-title">{{ fav.title }}</span>
              <span class="r-chip">{{ fav.theme }}</span>
            </button>
          </li>
          <li v-if="favs.length === 0">
            <p class="r-empty">
              还没有收藏,看到喜欢的卡片点一颗星
            </p>
          </li>
        </ul>
      </article>

      <article class="me-card">
        <button
          type="button"
          class="card-head"
          data-testid="path-card"
          @click="goPath"
        >
          <span class="c-icon"><KeIcon name="path" /></span>
          <span class="c-text">
            <span class="c-name">我的路径</span>
            <span class="c-sub">断点续探,随时回望</span>
          </span>
          <span class="c-count">{{ sessionTotal ?? '—' }}</span>
          <span class="c-arrow"><KeIcon name="chev" /></span>
        </button>
      </article>

      <article class="me-card">
        <button
          type="button"
          class="card-head"
          data-testid="summary-card"
          @click="goPath"
        >
          <span class="c-icon"><KeIcon name="layers" /></span>
          <span class="c-text">
            <span class="c-name">路径与成果</span>
            <span class="c-sub">整理发现与成果都在路径内</span>
          </span>
          <span class="c-arrow"><KeIcon name="chev" /></span>
        </button>
      </article>

      <article class="me-card">
        <button
          type="button"
          class="card-head"
          data-testid="entry-toggle"
          :aria-expanded="showEntries"
          @click="showEntries = !showEntries"
        >
          <span class="c-icon"><KeIcon name="compass" /></span>
          <span class="c-text">
            <span class="c-name">私人入口</span>
            <span class="c-sub">你创建的服务与跳转入口</span>
          </span>
          <span class="c-count">{{ entryTotal ?? '—' }}</span>
          <span
            class="c-arrow"
            :class="{ open: showEntries }"
          ><KeIcon name="chev" /></span>
        </button>
        <ul v-if="showEntries">
          <li
            v-for="entry in entries"
            :key="entry.id"
          >
            <div class="row static">
              <span class="r-title">{{ entry.name }}</span>
              <span
                class="r-chip"
                :data-status="entry.status"
              >{{ ENTRY_STATUS_LABELS[entry.status] ?? entry.status }}</span>
            </div>
            <p class="r-sub">
              {{ entry.cardTitle }}
            </p>
          </li>
          <li v-if="entries.length === 0">
            <p class="r-empty">
              还没有私人入口,在卡片页用一句话创建
            </p>
          </li>
        </ul>
      </article>
    </section>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; background: var(--ke-bg); padding: 32px 24px 60px; }
.title { margin: 0 0 14px; font-family: var(--ke-font-display); line-height: 1.3; color: var(--ke-ink); font-size: 34px; font-weight: 400; margin-bottom: 20px; }

/* 配额卡 */
.quota-card { display: flex; flex-direction: column; gap: 8px; margin-bottom: 14px; padding: 14px 16px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.q-head { display: flex; align-items: baseline; justify-content: space-between; }
.q-label { font-size: 13px; font-weight: 700; color: var(--ke-ink); }
.q-num { color: var(--ke-ink); font-size: 13px; font-weight: 700; font-variant-numeric: tabular-nums; }
.q-num.is-full { color: var(--ke-warn); }
.q-bar { height: 8px; border-radius: var(--ke-radius-full); background: var(--ke-line-2); overflow: hidden; }
.q-fill { height: 100%; border-radius: var(--ke-radius-full); background: var(--ke-primary); transition: width var(--ke-dur-fast) var(--ke-ease); }

/* 用尽变警示(04 §8.6) */
.q-fill.is-full { background: var(--ke-warn); }
.q-hint { margin: 0; font-size: 12px; color: var(--ke-sub); }
.q-hint.is-full { color: var(--ke-warn); }

/* 入口卡 */
.entries { display: flex; flex-direction: column; gap: 10px; }
.me-card { border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.me-card ul { list-style: none; margin: 0; padding: 4px 14px 10px; border-top: 1px solid var(--ke-line); }
.card-head { display: flex; width: 100%; align-items: center; gap: 10px; padding: 12px 14px; border: none; background: transparent; text-align: left; cursor: pointer; box-sizing: border-box; }
.card-head:active { background: var(--ke-primary-soft); }
.c-icon { display: flex; width: 36px; height: 36px; flex-shrink: 0; align-items: center; justify-content: center; border-radius: var(--ke-radius-s); background: var(--ke-primary-soft); color: var(--ke-primary); }
.c-text { flex: 1; min-width: 0; }
.c-name { display: block; font-size: 14px; font-weight: 600; line-height: 1.5; color: var(--ke-ink); }
.c-sub { display: block; margin-top: 2px; font-size: 12px; line-height: 1.5; color: var(--ke-sub); }
.c-count { flex-shrink: 0; color: var(--ke-ink); font-size: 15px; font-weight: 700; font-variant-numeric: tabular-nums; }
.c-arrow { display: flex; flex-shrink: 0; color: var(--ke-sub-2); transform: rotate(90deg); transition: transform var(--ke-dur-fast) var(--ke-ease); }
.c-arrow.open { transform: rotate(-90deg); }

.row { display: flex; width: 100%; align-items: center; gap: 8px; padding: 8px 0; border: none; background: transparent; text-align: left; cursor: pointer; }
.row.static { cursor: default; }
.r-title { flex: 1; min-width: 0; overflow: hidden; color: var(--ke-ink); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.r-chip { flex-shrink: 0; padding: 1px 8px; border-radius: var(--ke-radius-full); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 11px; font-weight: 600; }
.r-chip[data-status='PENDING'] { background: var(--ke-warn-soft); color: var(--ke-warn); }
.r-chip[data-status='DISABLED'] { background: var(--ke-line-2); color: var(--ke-sub); }
.r-sub { margin: -4px 0 6px; font-size: 12px; color: var(--ke-sub); }
.r-empty { margin: 0; padding: 8px 0; font-size: 12px; color: var(--ke-sub); }
</style>
