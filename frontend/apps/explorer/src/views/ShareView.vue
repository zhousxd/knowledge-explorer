<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { KeIcon } from '@ke/shared';
import { ApiError, TOKEN_KEY } from '../api/http';
import { continueShare, fetchPublicShare } from '../api/shares';
import type { PublicShare, ShareSnapshotNode } from '../api/shares';
import { formatShortDateTime } from '../format';

/**
 * 免登录分享页(接收者视角,04 §7.2 形态,FR-H04/H05/H07 界面,Task 32):
 * 路由 /s/:token,meta.public 匿名可浏览(P3 匿名链路已有);懒加载独立 chunk,
 * **不引 Vant**(原生元素+var(--ke-*) 自足样式,全局 tokens 由应用入口注入),
 * 保证分享直达的体积门(chunk gzip ≤ 50KB)。
 * GET /s/{token}(匿名可读)→ 404(撤销/不存在/快照缺失统一不泄露存在性)渲染空态
 * 「链接不存在或已被撤销」;成功渲染:宋体标题+摘要+「来源:探索分享」小字+
 * continueNotice 差异提示条(FR-H07 一期:静态文案随响应下发)+路径时间线
 * (快照 nodes 顺序:removed 节点占位灰行显示 note;正常节点=题/问+时间+
 * entries 关系列 name · relationLabel)+「沿此路径继续」:已登录 POST continue 成功
 * 跳 /path?sessionId={新};匿名(登录后回到本页逻辑一期不做)→ 轻提示「登录后可接续」
 * 并跳 /login,按钮文案相应为「登录后接续」。提示用页内轻 toast(不依赖 Vant)。
 */
const route = useRoute();
const router = useRouter();

const share = ref<PublicShare | null>(null);
const loading = ref(true);
/** 404(撤销/不存在)与网络等加载失败共用空态,文案区分 */
const missing = ref(false);
const missSub = ref('');

/** 匿名常态:挂载时读一次 TOKEN_KEY 定按钮文案与接续行为(页内不发生登录态变化) */
const loggedIn = ref(Boolean(localStorage.getItem(TOKEN_KEY)));
const continuing = ref(false);

// —— 页内轻 toast(自足实现,替代 Vant showToast 保住体积门) ——

const toastMsg = ref('');
let toastTimer: number | undefined;

function toast(msg: string): void {
  toastMsg.value = msg;
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => {
    toastMsg.value = '';
  }, 2000);
}

const nodes = computed<ShareSnapshotNode[]>(() => share.value?.snapshot?.nodes ?? []);
const displayTitle = computed(() => share.value?.title || share.value?.snapshot?.title || '探索分享');
const displaySummary = computed(() => share.value?.summary || share.value?.snapshot?.summary || '');

/** removed 占位行文案=note(后端「内容已不可用」),正常行标题回落追问文本 */
function nodeTitle(n: ShareSnapshotNode): string {
  return n.removed ? (n.note || '内容已不可用') : (n.title || n.question || '未命名节点');
}

async function load(): Promise<void> {
  loading.value = true;
  missing.value = false;
  const token = String(route.params.token ?? '');
  try {
    share.value = await fetchPublicShare(token);
  } catch (e) {
    missing.value = true;
    if (e instanceof ApiError && e.code === 404) {
      missSub.value = ''; // 撤销/不存在统一文案,不泄露存在性
    } else {
      missSub.value = e instanceof ApiError ? e.message : '加载失败,请稍后重试';
    }
  } finally {
    loading.value = false;
  }
}

async function onContinue(): Promise<void> {
  if (continuing.value || share.value === null) return;
  if (!loggedIn.value) {
    // 一期不做「登录后回到本页」:轻提示 + 去登录
    toast('登录后可接续');
    void router.push('/login');
    return;
  }
  continuing.value = true;
  try {
    const result = await continueShare(String(route.params.token ?? ''));
    void router.push({ path: '/path', query: { sessionId: String(result.sessionId) } });
  } catch (e) {
    toast(e instanceof ApiError ? e.message : '操作失败,请稍后重试');
  } finally {
    continuing.value = false;
  }
}

onMounted(() => {
  void load();
});

onBeforeUnmount(() => {
  window.clearTimeout(toastTimer);
});
</script>

<template>
  <div class="sv-page">
    <!-- 加载态 -->
    <main
      v-if="loading"
      class="sv-wrap sv-center"
    >
      <p
        class="sv-state"
        role="status"
      >
        加载中…
      </p>
    </main>

    <!-- 空态:撤销/不存在统一 404(不泄露存在性)或加载失败 -->
    <main
      v-else-if="missing"
      class="sv-wrap sv-center miss"
    >
      <KeIcon
        class="miss-ic"
        name="alert"
      />
      <b class="miss-t">链接不存在或已被撤销</b>
      <span
        v-if="missSub"
        class="miss-s"
      >{{ missSub }}</span>
    </main>

    <!-- 接收者视角:标题/摘要/来源 + 差异提示条 + 路径时间线 -->
    <main
      v-else
      class="sv-wrap"
    >
      <h1 class="sv-title">
        {{ displayTitle }}
      </h1>
      <p
        v-if="displaySummary"
        class="sv-summary"
      >
        {{ displaySummary }}
      </p>
      <p class="sv-source">
        来源:探索分享
      </p>

      <p
        v-if="share?.continueNotice"
        class="sv-notice"
        role="note"
      >
        <KeIcon
          class="notice-ic"
          name="alert"
        />
        {{ share.continueNotice }}
      </p>

      <ol class="tl">
        <li
          v-for="(n, i) in nodes"
          :key="n.nodeRef ?? i"
          class="tl-row"
          :class="{ removed: n.removed }"
        >
          <span class="tl-no">
            {{ i + 1 }}
          </span>
          <div class="tl-main">
            <template v-if="n.removed">
              <span class="tl-ph">
                {{ nodeTitle(n) }}
              </span>
            </template>
            <template v-else>
              <div class="tl-head">
                <span class="tl-t">
                  {{ nodeTitle(n) }}
                </span>
                <span
                  v-if="n.visitedAt"
                  class="tl-time"
                >
                  {{ formatShortDateTime(n.visitedAt) }}
                </span>
              </div>
              <span
                v-for="(e, ei) in n.entries ?? []"
                :key="ei"
                class="tl-entry"
              >
                {{ e.name }} · {{ e.relationLabel }}
              </span>
            </template>
          </div>
        </li>
      </ol>
    </main>

    <!-- 接续条:已登录直接接续;匿名去登录(按钮文案区分) -->
    <div
      v-if="share"
      class="sv-bar"
    >
      <button
        type="button"
        class="cont-go"
        :disabled="continuing"
        @click="onContinue"
      >
        {{ loggedIn ? '沿此路径继续' : '登录后接续' }}
      </button>
    </div>

    <!-- 页内轻 toast -->
    <div
      v-if="toastMsg"
      class="sv-toast"
      role="status"
    >
      {{ toastMsg }}
    </div>

    <!-- AI 生成标识(P8-36 合规硬门槛,页级常驻:加载/空态/正常态均显示) -->
    <footer class="ai-note">
      本页内容由人工智能辅助生成，仅供参考
    </footer>
  </div>
</template>

<style scoped>
/* 自足轻量样式:仅用 var(--ke-*)(全局 tokens 由应用入口注入),不依赖 Vant */
.sv-page { min-height: 100vh; box-sizing: border-box; padding: 24px 16px 110px; background: var(--ke-bg); }
.sv-wrap { margin: 0 auto; max-width: 480px; }
.sv-center { max-width: 320px; margin-top: 60px; text-align: center; }
.sv-state { margin: 0; text-align: center; font-size: 12px; color: var(--ke-sub); }

/* 头部:标题(宋体)/摘要/来源小字 */
.sv-title { margin: 8px 0 0; font-family: var(--ke-font-display); color: var(--ke-ink); overflow-wrap: anywhere; font-size: 30px; font-weight: 400; line-height: 1.5; }
.sv-summary { margin: 8px 0 0; font-size: 13px; line-height: 1.8; color: var(--ke-ink-2); overflow-wrap: anywhere; }
.sv-source { margin: 8px 0 0; font-size: 11px; color: var(--ke-sub-2); }

/* 差异提示条(warn soft,FR-H07 常驻) */
.sv-notice { display: flex; align-items: baseline; gap: 6px; margin: 12px 0 0; padding: 8px 12px; border-radius: var(--ke-radius-m); background: var(--ke-warn-soft); color: var(--ke-warn); font-size: 12px; line-height: 1.7; }
.notice-ic { flex-shrink: 0; width: 13px; height: 13px; }

/* 路径时间线:快照顺序,removed=占位灰行 */
.tl { margin: 16px 0 0; padding: 12px 13px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); list-style: none; }
.tl-row { display: flex; gap: 10px; padding: 9px 0; }
.tl-row + .tl-row { border-top: 1px solid var(--ke-line-2); }
.tl-no { flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; margin-top: 1px; border-radius: var(--ke-radius-full); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 11px; font-weight: 700; }
.tl-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.tl-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.tl-t { font-size: 14px; font-weight: 600; line-height: 1.6; color: var(--ke-ink); overflow-wrap: anywhere; }
.tl-time { flex-shrink: 0; font-size: 11px; color: var(--ke-sub-2); }
.tl-entry { align-self: flex-start; padding: 2px 8px; border-radius: var(--ke-radius-s); background: var(--ke-surface-2); color: var(--ke-sub); font-size: 11px; line-height: 1.6; }
.tl-ph { font-size: 13px; line-height: 1.7; color: var(--ke-sub-2); }
.tl-row.removed .tl-no { background: var(--ke-surface-2); color: var(--ke-sub-2); }

/* 空态 */
.miss-ic { width: 40px; height: 40px; color: var(--ke-sub-2); }
.miss-t { display: block; margin-top: 10px; font-size: 14px; font-weight: 600; color: var(--ke-ink); }
.miss-s { display: block; margin-top: 4px; font-size: 12px; color: var(--ke-sub); overflow-wrap: anywhere; }

/* 接续条 */
.sv-bar { position: fixed; left: 0; right: 0; bottom: 0; z-index: var(--ke-z-bar); padding: 10px 16px calc(10px + env(safe-area-inset-bottom, 0px)); background: var(--ke-surface); border-top: 1px solid var(--ke-line); }
.cont-go { display: block; width: 100%; max-width: 448px; margin: 0 auto; padding: 11px 0; border: none; border-radius: var(--ke-radius-m); background: var(--ke-primary); color: var(--ke-white); font-size: 14px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; transition: transform var(--ke-dur-fast) var(--ke-ease); }
.cont-go:active { transform: scale(0.98); }
.cont-go:disabled { opacity: 0.45; cursor: default; transform: none; }

/* 页内轻 toast(替代 Vant showToast) */
.sv-toast { position: fixed; left: 50%; bottom: 90px; z-index: var(--ke-z-toast); transform: translateX(-50%); max-width: 80vw; padding: 8px 16px; border-radius: var(--ke-radius-full); background: var(--ke-toast); color: var(--ke-white); font-size: 12px; line-height: 1.6; text-align: center; }

/* AI 生成标识(P8-36 合规硬门槛):页脚居中小字,页级常驻 */
.ai-note { margin: 24px 0 0; font-size: 11px; color: var(--ke-sub-2); text-align: center; }
</style>
