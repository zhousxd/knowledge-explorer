<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showToast } from 'vant';
import { KeIcon } from '@ke/shared';
import { ApiError } from '../api/http';
import { fetchSessionTree } from '../api/sessions';
import type { PathNode, SessionTree } from '../api/sessions';
import { createShare, revokeShare } from '../api/shares';
import type { ShareCreated } from '../api/shares';
import TopBar from '../components/TopBar.vue';

/**
 * 分享设置页(04 §7.2 形态,FR-H02/H03 界面,Task 32):受保护路由,query.sessionId 必带
 * (缺失回 /path —— 路径页必有会话)。复用 SummaryView 勾选模式:独立 fetchSessionTree 载入
 * 会话树 → 按根分组渲染节点复选框;**默认全部勾选**(P7-29 硬验收:勾选集=树内全部可见节点,
 * 无「隐藏分支」概念;未勾选的任何节点——含已勾选节点的子树——不入快照)。标题预填会话标题、
 * 摘要留空可填;warn soft 提示条说明私密内容已自动移除(快照过滤由后端 SnapshotFilter 兜底)。
 * 「生成分享链接」POST /api/shares(busy 防双击)→ 展示完整链接(origin+url)+复制
 * (clipboard API,失败 toast 手动复制)+「打开接收者视角」新标签;已生成后可撤销
 * (DELETE,幂等)→ 链接标记「已撤销」,可重新生成新链接。
 */
const route = useRoute();
const router = useRouter();

const sessionId = ref<number | null>(null);
const tree = ref<SessionTree | null>(null);
const treeLoading = ref(false);
const treeError = ref('');
const checked = ref<Set<number>>(new Set());
const submitting = ref(false);

// —— 表单:标题预填会话标题(goal 即会话标题),摘要留空可填 ——
const title = ref('');
const summary = ref('');

// —— 已生成态:非 null=已生成;revoked=true 链接已撤销(可重新生成新链接) ——
const created = ref<ShareCreated | null>(null);
const revoked = ref(false);
const copying = ref(false);

/** 数字串 query 解析(防 /share/new?sessionId=abc 之类脏参,SummaryView 同则) */
function numericQuery(key: string): number | null {
  const raw = route.query[key];
  return typeof raw === 'string' && /^\d+$/.test(raw) ? Number(raw) : null;
}

// —— 按根分组(与 SummaryView 同则:visited_at 升序建 childrenMap 后自每根 DFS) ——

interface PickRow {
  node: PathNode;
  /** 距所属根的深度(根行 0 不入行,子孙 ≥1 按级缩进) */
  depth: number;
}
interface BranchGroup {
  root: PathNode;
  rows: PickRow[];
}

const groups = computed<BranchGroup[]>(() => {
  const childrenMap = new Map<number | null, PathNode[]>();
  for (const n of tree.value?.nodes ?? []) {
    const list = childrenMap.get(n.parentNodeId);
    if (list) list.push(n);
    else childrenMap.set(n.parentNodeId, [n]);
  }
  const out: BranchGroup[] = [];
  for (const root of childrenMap.get(null) ?? []) {
    const rows: PickRow[] = [];
    const walk = (parent: number, depth: number): void => {
      for (const child of childrenMap.get(parent) ?? []) {
        rows.push({ node: child, depth });
        walk(child.nodeId, depth + 1);
      }
    };
    walk(root.nodeId, 1);
    out.push({ root, rows });
  }
  return out;
});

/** 勾选集=树内节点(P7-29):提交序取树序(与后端快照裁剪的输入顺序一致) */
const allIds = computed(() => (tree.value?.nodes ?? []).map((n) => n.nodeId));
const checkedCount = computed(() => allIds.value.filter((id) => checked.value.has(id)).length);

function nodeTitle(n: PathNode): string {
  return n.cardTitle || n.questionText || '新节点';
}

function onToggle(id: number, e: Event): void {
  const box = e.target as HTMLInputElement;
  if (box.checked) checked.value.add(id);
  else checked.value.delete(id);
}

async function loadTree(id: number): Promise<void> {
  treeLoading.value = true;
  treeError.value = '';
  try {
    tree.value = await fetchSessionTree(id);
    // 默认全部勾选:分享默认给全量路径,收窄是接收者的显式动作
    checked.value = new Set(allIds.value);
    // 标题预填会话标题(goal 即会话标题)
    title.value = tree.value.goal ?? '';
  } catch (e) {
    treeError.value = e instanceof ApiError ? e.message : '加载失败,请稍后重试';
  } finally {
    treeLoading.value = false;
  }
}

// —— 生成 / 复制 / 撤销 ——

const fullUrl = computed(() => (created.value ? `${window.location.origin}${created.value.url}` : ''));

async function generate(): Promise<void> {
  if (submitting.value || sessionId.value == null || checkedCount.value === 0) return;
  submitting.value = true;
  try {
    const trimmedTitle = title.value.trim();
    const trimmedSummary = summary.value.trim();
    const result = await createShare({
      objectType: 'SESSION',
      objectId: sessionId.value,
      nodeIds: allIds.value.filter((id) => checked.value.has(id)),
      ...(trimmedTitle ? { title: trimmedTitle } : {}),
      ...(trimmedSummary ? { summary: trimmedSummary } : {})
    });
    created.value = result;
    revoked.value = false;
  } catch (e) {
    showToast(e instanceof ApiError ? e.message : '操作失败,请稍后重试');
  } finally {
    submitting.value = false;
  }
}

let copyTimer: number | undefined;
/** 复制成功反馈:按钮短暂切「已复制」,2s 回落 */
const copied = ref(false);

async function copyLink(): Promise<void> {
  if (copying.value || created.value === null || revoked.value) return;
  copying.value = true;
  try {
    if (!navigator.clipboard) throw new Error('clipboard unavailable');
    await navigator.clipboard.writeText(fullUrl.value);
    copied.value = true;
    window.clearTimeout(copyTimer);
    copyTimer = window.setTimeout(() => {
      copied.value = false;
    }, 2000);
  } catch {
    showToast('复制失败,请长按链接手动复制');
  } finally {
    copying.value = false;
  }
}

const revoking = ref(false);

async function revoke(): Promise<void> {
  if (revoking.value || created.value === null || revoked.value) return;
  revoking.value = true;
  try {
    await revokeShare(created.value.token);
    revoked.value = true;
    showToast('已撤销,链接已失效');
  } catch (e) {
    showToast(e instanceof ApiError ? e.message : '操作失败,请稍后重试');
  } finally {
    revoking.value = false;
  }
}

onMounted(() => {
  const sid = numericQuery('sessionId');
  if (sid == null) {
    // 契约:分享设置页必带会话;脏链接回路径页
    void router.replace('/path');
    return;
  }
  sessionId.value = sid;
  void loadTree(sid);
});

onBeforeUnmount(() => {
  window.clearTimeout(copyTimer);
});
</script>

<template>
  <div class="page">
    <TopBar section="分享探索" />

    <main class="wrap">
      <p
        v-if="treeLoading"
        class="state"
      >
        加载中…
      </p>

      <div
        v-else-if="treeError"
        class="load-err"
      >
        <KeIcon
          class="le-ic"
          name="alert"
        />
        <b class="le-t">
          路径打不开
        </b>
        <span class="le-s">
          {{ treeError }}
        </span>
      </div>

      <template v-else-if="tree">
        <header class="head">
          <h1 class="head-t">
            分享这条路径
          </h1>
          <p class="head-s">
            勾选要收进分享的节点，未勾选的不会包含
          </p>
          <p
            class="hint"
            role="note"
          >
            <KeIcon
              class="hint-ic"
              name="lock"
            />
            分享前已自动移除私密内容；未勾选节点不会包含
          </p>
        </header>

        <div class="fields">
          <label class="f">
            <span class="f-label">标题</span>
            <input
              v-model="title"
              class="f-title"
              type="text"
              maxlength="60"
              placeholder="给这次探索起个名字（可不改）"
            >
          </label>
          <label class="f">
            <span class="f-label">摘要</span>
            <textarea
              v-model="summary"
              class="f-summary"
              rows="2"
              maxlength="200"
              placeholder="一句话介绍这次探索（可不填）"
            />
          </label>
        </div>

        <section
          v-for="(g, gi) in groups"
          :key="g.root.nodeId"
          class="branch"
          :aria-label="`分支 ${gi + 1}`"
        >
          <label class="b-row">
            <input
              class="ck"
              type="checkbox"
              :checked="checked.has(g.root.nodeId)"
              @change="onToggle(g.root.nodeId, $event)"
            >
            <span class="b-t">
              {{ nodeTitle(g.root) }}
            </span>
          </label>
          <label
            v-for="r in g.rows"
            :key="r.node.nodeId"
            class="n-row"
            :style="{ paddingLeft: `${16 + r.depth * 18}px` }"
          >
            <input
              class="ck"
              type="checkbox"
              :checked="checked.has(r.node.nodeId)"
              @change="onToggle(r.node.nodeId, $event)"
            >
            <span class="n-t">
              {{ nodeTitle(r.node) }}
            </span>
          </label>
        </section>

        <!-- 已生成态:链接 + 复制 + 接收者视角 + 撤销;撤销后标记已撤销 -->
        <section
          v-if="created"
          class="done"
        >
          <template v-if="!revoked">
            <b class="done-t">
              分享链接已生成
            </b>
            <input
              class="link"
              type="text"
              readonly
              :value="fullUrl"
              @focus="($event.target as HTMLInputElement).select()"
            >
            <div class="done-acts">
              <button
                type="button"
                class="copy"
                :disabled="copying"
                @click="copyLink"
              >
                {{ copied ? '已复制' : '复制链接' }}
              </button>
              <a
                class="open"
                :href="fullUrl"
                target="_blank"
                rel="noopener"
              >打开接收者视角</a>
            </div>
          </template>
          <template v-else>
            <p class="link-revoked">
              <KeIcon
                class="rv-ic"
                name="alert"
              />
              已撤销，链接已失效
            </p>
          </template>
          <button
            v-if="!revoked"
            type="button"
            class="revoke"
            :disabled="revoking"
            @click="revoke"
          >
            撤销分享
          </button>
        </section>
      </template>
    </main>

    <!-- 底部固定条:已选计数 + 生成(撤销后仍可重新生成新链接) -->
    <div
      v-if="tree !== null && !treeError"
      class="sum-bar"
    >
      <span class="sel-count">
        已选 {{ checkedCount }} 个节点
      </span>
      <button
        type="button"
        class="sum-go"
        :disabled="submitting || checkedCount === 0"
        @click="generate"
      >
        生成分享链接
      </button>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; padding: 52px 16px 120px; background: var(--ke-bg); }
.wrap { margin: 14px auto 0; max-width: 480px; }
.state { margin: 40px 0 0; text-align: center; font-size: 12px; color: var(--ke-sub); }

/* 头部与提示条 */
.head-t { margin: 0; font-family: var(--ke-font-display); font-size: 20px; font-weight: 900; color: var(--ke-ink); }
.head-s { margin: 6px 0 0; font-size: 12px; line-height: 1.7; color: var(--ke-sub); }
.hint { display: flex; align-items: baseline; gap: 6px; margin: 10px 0 0; padding: 8px 12px; border-radius: var(--ke-radius-m); background: var(--ke-warn-soft); color: var(--ke-warn); font-size: 12px; line-height: 1.7; }
.hint-ic { flex-shrink: 0; width: 13px; height: 13px; }

/* 标题/摘要表单 */
.fields { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; padding: 12px 13px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.f { display: flex; flex-direction: column; gap: 5px; }
.f-label { font-size: 12px; font-weight: 700; color: var(--ke-sub); }
.f-summary { resize: vertical; }
.f-title, .f-summary { box-sizing: border-box; width: 100%; padding: 8px 10px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-m); background: var(--ke-surface); color: var(--ke-ink); font-size: 13px; font-family: var(--ke-font); line-height: 1.6; }
.f-title:focus, .f-summary:focus { outline: none; border-color: var(--ke-primary); box-shadow: var(--ke-focus); }


/* 分支分组:根行=分支标题,子孙按深度缩进(padding-left 内联注入) */
.branch { margin-top: 10px; padding: 4px 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.b-row, .n-row { display: flex; align-items: baseline; gap: 9px; padding: 8px 0; font-size: 13px; line-height: 1.6; color: var(--ke-ink); cursor: pointer; }
.n-row + .n-row, .b-row + .n-row { border-top: 1px solid var(--ke-line-2); }
.b-t { font-weight: 700; }
.ck { flex-shrink: 0; width: 15px; height: 15px; margin: 0; accent-color: var(--ke-primary); cursor: pointer; }

/* 已生成态 */
.done { margin-top: 14px; padding: 12px 13px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.done-t { font-size: 13px; font-weight: 700; color: var(--ke-ink); }
.link { box-sizing: border-box; width: 100%; margin-top: 8px; padding: 8px 10px; border: 1px solid var(--ke-line-2); border-radius: var(--ke-radius-m); background: var(--ke-surface-2); color: var(--ke-ink); font-size: 12px; font-family: var(--ke-font); overflow-wrap: anywhere; }
.done-acts { display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.copy { flex-shrink: 0; padding: 7px 14px; border: none; border-radius: var(--ke-radius-m); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 12px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; }
.copy:disabled { opacity: 0.45; cursor: default; }
.open { font-size: 12px; font-weight: 700; color: var(--ke-primary); text-decoration: none; }
.open:hover { text-decoration: underline; }
.link-revoked { display: flex; align-items: baseline; gap: 6px; margin: 0; color: var(--ke-warn); font-size: 13px; font-weight: 700; }
.rv-ic { flex-shrink: 0; width: 13px; height: 13px; }
.revoke { display: block; width: 100%; margin-top: 10px; padding: 8px 0; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-m); background: var(--ke-surface); color: var(--ke-danger); font-size: 12px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; }
.revoke:disabled { opacity: 0.45; cursor: default; }

/* 底部固定条:已选计数 + 生成 */
.sum-bar { position: fixed; left: 0; right: 0; bottom: 0; z-index: var(--ke-z-bar); display: flex; align-items: center; gap: 10px; padding: 10px 12px calc(10px + env(safe-area-inset-bottom, 0px)); background: var(--ke-surface); border-top: 1px solid var(--ke-line); }
.sel-count { flex: 1; min-width: 0; font-size: 12px; color: var(--ke-sub); }
.sum-go { flex-shrink: 0; padding: 10px 22px; border: none; border-radius: var(--ke-radius-m); background: var(--ke-primary); color: var(--ke-white); font-size: 13px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; transition: transform var(--ke-dur-fast) var(--ke-ease); }
.sum-go:active { transform: scale(0.97); }
.sum-go:disabled { opacity: 0.45; cursor: default; transform: none; }

/* 树加载失败空态(SummaryView 同则) */
.load-err { margin: 60px auto 0; max-width: 320px; text-align: center; }
.le-ic { width: 40px; height: 40px; color: var(--ke-sub-2); }
.le-t { display: block; margin-top: 10px; font-size: 14px; font-weight: 600; color: var(--ke-ink); }
.le-s { display: block; margin-top: 4px; font-size: 12px; color: var(--ke-sub); overflow-wrap: anywhere; }
</style>
