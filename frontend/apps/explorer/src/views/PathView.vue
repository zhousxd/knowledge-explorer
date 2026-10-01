<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showConfirmDialog, showToast } from 'vant';
import { KeIcon } from '@ke/shared';
import { ApiError } from '../api/http';
import { fetchLatestSession } from '../api/sessions';
import type { AddNodePayload, ExplainLevel, PathNode } from '../api/sessions';
import PathTree from '../components/PathTree.vue';
import TopBar from '../components/TopBar.vue';
import { usePathStore } from '../stores/path';

/**
 * 我的路径页(04 §7.2 PathNode + §8.2 空态):解释档位 chip(FR-E10)→ 首次引导条 →
 * 路径树 → 底部三键停顿区。受保护路由(非 public):?sessionId= 直载,否则回落最近会话,
 * 无会话显示空态引导去首页。回到历史节点 = 底部浮条确认;带 cardVersionId/entryId 的
 * 跳转进入(卡片页 Phase 5 接线)确认时挂新节点成分支,纯确认只切当前。
 */
const route = useRoute();
const router = useRouter();
const store = usePathStore();

const LEVELS: { value: ExplainLevel; label: string }[] = [
  { value: 'SIMPLE', label: '简明' },
  { value: 'DEEP', label: '深入' },
  { value: 'CHILD', label: '儿童' }
];

/** 首次进入引导条(Q3:解释「回到节点=新分支」);关闭后本地记忆不再打扰 */
const GUIDE_KEY = 'ke_ex_path_guide';
const guide = ref(!localStorage.getItem(GUIDE_KEY));

const latestLoading = ref(false);
const bootstrapError = ref('');

/** 数字串 query 解析(防 /path?sessionId=abc 之类脏参) */
function numericQuery(key: string): number | null {
  const raw = route.query[key];
  return typeof raw === 'string' && /^\d+$/.test(raw) ? Number(raw) : null;
}

async function bootstrap(): Promise<void> {
  const id = numericQuery('sessionId');
  if (id != null) {
    await store.loadTree(id);
    return;
  }
  latestLoading.value = true;
  try {
    const latest = await fetchLatestSession();
    if (latest) await store.loadTree(latest.sessionId);
  } catch (e) {
    bootstrapError.value = e instanceof ApiError ? e.message : '加载失败,请稍后重试';
  } finally {
    latestLoading.value = false;
  }
}

onMounted(() => {
  void bootstrap();
});

const booting = computed(() => store.loading || latestLoading.value);
/** 空态:无会话可展示(引导去首页从卡片开始) */
const isEmpty = computed(() => !booting.value && store.sessionId == null && !bootstrapError.value);
/** 树已可渲染(区别于 403/404 错误态) */
const loaded = computed(() => store.sessionId != null && !store.missing);

// —— 回到此节点:浮条确认。跳转参数(cardVersionId/entryId)只在卡片页跳入时存在,
//    有参确认=在该节点下挂新节点(FR-E05 分支);纯确认仅把当前游标切过去 ——
const pending = ref<PathNode | null>(null);
const confirming = ref(false);
const pendingPayload = computed<AddNodePayload | null>(() => {
  const cardVersionId = numericQuery('cardVersionId');
  const entryId = numericQuery('entryId');
  if (cardVersionId == null && entryId == null) return null;
  return { cardVersionId: cardVersionId ?? undefined, entryId: entryId ?? undefined };
});

function onPick(node: PathNode): void {
  store.setCurrent(node.nodeId);
  pending.value = node;
}

async function confirmResume(): Promise<void> {
  if (!pending.value || confirming.value) return;
  confirming.value = true;
  try {
    if (pendingPayload.value) {
      await store.addNodeAt(pending.value.nodeId, pendingPayload.value);
      showToast('已在该节点下开始新分支');
    }
  } catch (e) {
    showToast(e instanceof ApiError ? e.message : '操作失败,请稍后重试');
  } finally {
    confirming.value = false;
    pending.value = null;
  }
}

// —— 讲解档位(FR-E10):当前项主色实底,点击 PATCH 会话内记忆 ——
const levelBusy = ref(false);

async function onLevel(level: ExplainLevel): Promise<void> {
  if (levelBusy.value || level === store.explainLevel) return;
  levelBusy.value = true;
  try {
    await store.patchExplainLevel(level);
  } catch (e) {
    showToast(e instanceof ApiError ? e.message : '操作失败,请稍后重试');
  } finally {
    levelBusy.value = false;
  }
}

// —— 底部三键停顿区:整理发现(Phase 5 成果整理)/ 暂存(直接离开)/ 换个方向(确认回首页) ——
function onOrganize(): void {
  console.info('整理发现由 Phase 5 接线(成果整理服务)');
}

/** 状态已随每次操作自动持久化,暂存=直接离开 */
function onStash(): void {
  showToast('已暂存,可随时回来');
  void router.push('/home');
}

async function onRedirect(): Promise<void> {
  try {
    await showConfirmDialog({
      title: '换个方向',
      message: '回到首页重新选一张卡片开始。当前路径已自动保存。'
    });
  } catch {
    return; // 用户取消
  }
  void router.push('/home');
}

function closeGuide(): void {
  guide.value = false;
  localStorage.setItem(GUIDE_KEY, '1');
}

function goHome(): void {
  void router.push('/home');
}

function nodeTitle(n: PathNode): string {
  return n.cardTitle || n.questionText || '新节点';
}
</script>

<template>
  <div class="page">
    <TopBar
      section="我的路径"
      :current="loaded ? store.title : ''"
      path-active
    />

    <p
      v-if="booting"
      class="state"
    >
      加载中…
    </p>

    <div
      v-else-if="isEmpty"
      class="empty"
    >
      <KeIcon
        class="empty-ic"
        name="compass"
      />
      <b class="empty-t">
        还没有探索路径
      </b>
      <span class="empty-s">
        从首页任一张卡片开始
      </span>
      <button
        type="button"
        class="empty-btn"
        @click="goHome"
      >
        去首页逛逛
      </button>
    </div>

    <div
      v-else-if="bootstrapError || store.missing"
      class="empty"
    >
      <KeIcon
        class="empty-ic"
        name="alert"
      />
      <b class="empty-t">
        路径打不开
      </b>
      <span class="empty-s">
        {{ bootstrapError || store.errorMsg }}
      </span>
      <button
        type="button"
        class="empty-btn"
        @click="goHome"
      >
        回首页看看
      </button>
    </div>

    <main
      v-else-if="loaded"
      class="scroll"
    >
      <div
        class="levels"
        role="group"
        aria-label="讲解档位"
      >
        <span class="lv-label">
          讲解档位
        </span>
        <button
          v-for="lv in LEVELS"
          :key="lv.value"
          type="button"
          class="lv"
          :class="{ on: store.explainLevel === lv.value }"
          @click="onLevel(lv.value)"
        >
          {{ lv.label }}
        </button>
      </div>

      <div
        v-if="guide"
        class="guide"
      >
        <span>回到任一节点继续，新的探索会成为它的分支</span>
        <button
          type="button"
          class="guide-x"
          @click="closeGuide"
        >
          知道了
        </button>
      </div>

      <PathTree @pick="onPick" />

      <p class="tail">
        共 {{ store.nodes.length }} 个节点 · {{ store.branchIds.size }} 个分支
      </p>
    </main>

    <div
      v-if="pending"
      class="resume-bar"
      role="status"
    >
      <span class="rb-text">
        将在「{{ nodeTitle(pending) }}」下继续探索
      </span>
      <button
        type="button"
        class="rb-go"
        :disabled="confirming"
        @click="confirmResume"
      >
        在这里继续
      </button>
      <button
        type="button"
        class="rb-x"
        @click="pending = null"
      >
        取消
      </button>
    </div>

    <nav
      class="pausebar"
      aria-label="停顿区"
    >
      <button
        type="button"
        class="pb"
        @click="onOrganize"
      >
        <KeIcon
          class="pb-ic"
          name="layers"
        />
        整理发现
      </button>
      <button
        type="button"
        class="pb"
        @click="onStash"
      >
        <KeIcon
          class="pb-ic"
          name="clock"
        />
        暂存
      </button>
      <button
        type="button"
        class="pb"
        @click="onRedirect"
      >
        <KeIcon
          class="pb-ic"
          name="home"
        />
        换个方向
      </button>
    </nav>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; padding: 52px 16px 170px; background: var(--ke-bg); }
.state { margin: 40px 0 0; text-align: center; font-size: 12px; color: var(--ke-sub); }
.levels { display: flex; align-items: center; gap: 8px; margin: 2px 0 10px; padding: 10px 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.lv-label { font-size: 12px; font-weight: 700; color: var(--ke-sub); }
.lv { padding: 3px 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-full); background: var(--ke-surface); color: var(--ke-sub); font-size: 11px; font-weight: 600; font-family: var(--ke-font); cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); }
.lv.on { border-color: var(--ke-primary); background: var(--ke-primary); color: var(--ke-white); }
.guide { display: flex; align-items: center; gap: 8px; margin: 0 0 8px; padding: 9px 12px; border-radius: var(--ke-radius-m); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 12px; line-height: 1.6; }
.guide span { flex: 1; min-width: 0; }
.guide-x { flex-shrink: 0; border: none; background: transparent; color: var(--ke-primary); font-size: 12px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; }
.tail { margin: 12px 0 0; text-align: center; font-size: 11px; color: var(--ke-sub-2); }
.empty { margin: 60px auto 0; max-width: 320px; text-align: center; }
.empty-ic { width: 40px; height: 40px; color: var(--ke-sub-2); }
.empty-t { display: block; margin-top: 10px; font-size: 14px; font-weight: 600; color: var(--ke-ink); }
.empty-s { display: block; margin-top: 4px; font-size: 12px; color: var(--ke-sub); }
.empty-btn { display: inline-block; margin-top: 14px; padding: 8px 18px; border: none; border-radius: var(--ke-radius-m); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 13px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; }

/* 浮条:压在停顿区之上,提示将挂载的父节点 */
.resume-bar { position: fixed; left: 12px; right: 12px; bottom: calc(70px + env(safe-area-inset-bottom, 0px)); z-index: var(--ke-z-bar); display: flex; align-items: center; gap: 8px; padding: 10px 12px; border: 1px solid var(--ke-primary); border-radius: var(--ke-radius-l); background: var(--ke-surface); box-shadow: var(--ke-shadow-2); }
.rb-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--ke-ink); }
.rb-go { flex-shrink: 0; padding: 7px 12px; border: none; border-radius: var(--ke-radius-m); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 12px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; }
.rb-go:disabled { opacity: 0.45; cursor: default; }
.rb-x { flex-shrink: 0; border: none; background: transparent; color: var(--ke-sub); font-size: 12px; font-family: var(--ke-font); cursor: pointer; }
.pausebar { position: fixed; left: 0; right: 0; bottom: 0; z-index: var(--ke-z-bar); display: flex; gap: 8px; padding: 10px 12px calc(10px + env(safe-area-inset-bottom, 0px)); background: var(--ke-surface); border-top: 1px solid var(--ke-line); }
.pb { display: flex; flex: 1; align-items: center; justify-content: center; gap: 5px; padding: 10px 0; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); background: var(--ke-surface); color: var(--ke-ink); font-size: 13px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; transition: transform var(--ke-dur-fast) var(--ke-ease); }
.pb:active { transform: scale(0.97); }
.pb-ic { width: 15px; height: 15px; }
</style>
