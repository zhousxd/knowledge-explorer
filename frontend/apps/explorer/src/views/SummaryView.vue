<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showToast } from 'vant';
import { ClaimBadge, KeIcon } from '@ke/shared';
import { ApiError } from '../api/http';
import { fetchRun, isTerminal, summarize } from '../api/runs';
import type { RunState, RunSubmitPayload, SummarizePayload } from '../api/runs';
import { fetchOpenQuestions, fetchSessionTree } from '../api/sessions';
import type { OpenQuestionItem, PathNode, SessionTree } from '../api/sessions';
import TopBar from '../components/TopBar.vue';
import { useSessionStore } from '../stores/sessionStore';
import { formatShortDateTime } from '../format';

/**
 * 成果整理页(04 §7.2 SummaryView,FR-E07/E09 界面):受保护路由,query.sessionId 必带
 * (缺失回 /path —— 路径页必有会话);?runId 存在时进入报告态,否则勾选态。
 * 勾选态:独立 fetchSessionTree 载入会话树(不污染 path store)→ 按根分组渲染节点复选框
 * (根行=分支标题、子孙按深度缩进,跨分支勾选),默认全不选 + 顶部「全选」;底部固定条
 * 已选计数 + 「生成探索报告」(空选禁用/busy 防双击)→ POST /agent/summarize → 带查询参数
 * (runId)与 state(keSummary 提交上下文,供重试)原地切报告态;429 配额文案页内展示。
 * 报告态:简版轮询 GET /runs/{id}(2s/终态停/卸载清,P5-21 先例的页内简版)——QUEUED/RUNNING
 * spinner「整理中」;FAILED/TIMEOUT 错误卡 + 重试(同一 payload 重新 summarize,刷新丢上下文
 * 则禁用带原因);DONE 渲染探索报告:宋体标题 → 分支视图(branchView 每支=根题+子节点题列表)
 * → 关键发现(每条 ClaimBadge 三档 + citations 角标 → 出处清单行高亮,P5-22 同模式)→ 报告
 * 未决疑问(clock 图标)→ 出处清单(sources map + stripped 警示)→ disclaimer 脚注。
 * 页面底部「还有哪些疑问」卡(FR-E09):GET open-questions 渲染会话全部遗留疑问(每条含来源
 * 时间),静态展示 + 「去追问」fetchRun 取 submitContext(review P5-FIX)组装 keRun 提交上下文
 * 跳该来源 run 的 /runs/{runId} 结果页追问(落地即可追问;取不到 toast「暂时无法追问」不跳转)。
 */
const POLL_MS = 2000;
/** 出处清单行文本截断(sources map 快照文本 → 行 snippet,与 RunView 同参) */
const SNIPPET_MAX = 30;
/** 角标 title 的出处文本缩略(首 12 字) */
const CITE_TITLE_MAX = 12;
const HIGHLIGHT_MS = 2000;

const route = useRoute();
const router = useRouter();
const sessionStore = useSessionStore();

// —— 会话上下文(query 驻留,勾选/报告两态共用) ——

const sessionId = ref<number | null>(null);
/** 报告态当前轮询的 run id(非 null=报告态);进态只发生在挂载与 enterRun,不随路由被动跟随 */
const activeRunId = ref<number | null>(null);

/** 数字串 query 解析(防 /summary?sessionId=abc 之类脏参,PathView 同则) */
function numericQuery(key: string): number | null {
  const raw = route.query[key];
  return typeof raw === 'string' && /^\d+$/.test(raw) ? Number(raw) : null;
}

// —— 勾选态:会话树(独立加载,不复用 path store)+ 跨分支勾选 ——

const tree = ref<SessionTree | null>(null);
const treeLoading = ref(false);
const treeError = ref('');
const checked = ref<Set<number>>(new Set());
const submitting = ref(false);
/** 提交/重试遇 429:配额 envelope 文案页内展示(04 §8.6),勾选/报告两态共用 */
const quotaMsg = ref('');

/** 会话未决疑问(FR-E09):静默加载,失败不提示(附属卡,缺失不阻断主流程) */
const oqs = ref<OpenQuestionItem[]>([]);

interface PickRow {
  node: PathNode;
  /** 距所属根的深度(根行 0 不入行,子孙 ≥1 按级缩进) */
  depth: number;
}
interface BranchGroup {
  root: PathNode;
  rows: PickRow[];
}

/**
 * 按根分组(与后端 REPORT.branchView 同语义:每个选中根一支):visited_at 升序建
 * childrenMap 后自每根 DFS,同父下保持时间序;纯追问节点标题回落 questionText。
 */
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

const allIds = computed(() => (tree.value?.nodes ?? []).map((n) => n.nodeId));
const checkedCount = computed(() => allIds.value.filter((id) => checked.value.has(id)).length);
const allSelected = computed(() => allIds.value.length > 0 && checkedCount.value === allIds.value.length);

function nodeTitle(n: PathNode): string {
  return n.cardTitle || n.questionText || '新节点';
}

function onToggle(id: number, e: Event): void {
  const box = e.target as HTMLInputElement;
  if (box.checked) checked.value.add(id);
  else checked.value.delete(id);
}

function selectAll(): void {
  for (const id of allIds.value) checked.value.add(id);
}

async function loadTree(id: number): Promise<void> {
  treeLoading.value = true;
  treeError.value = '';
  try {
    tree.value = await fetchSessionTree(id);
  } catch (e) {
    treeError.value = e instanceof ApiError ? e.message : '加载失败,请稍后重试';
  } finally {
    treeLoading.value = false;
  }
}

/** 生成探索报告:同一 payload 随 state 带入报告态供重试;成功后原地切报告态续拍 */
async function submit(): Promise<void> {
  if (submitting.value || sessionId.value == null || checkedCount.value === 0) return;
  submitting.value = true;
  try {
    const payload: SummarizePayload = {
      sessionId: sessionId.value,
      nodeIds: allIds.value.filter((id) => checked.value.has(id))
    };
    const { runId } = await summarize(payload);
    await enterRun(runId, payload);
  } catch (e) {
    if (e instanceof ApiError && e.code === 429) {
      quotaMsg.value = e.message;
    } else {
      showToast(e instanceof ApiError ? e.message : '操作失败,请稍后重试');
    }
  } finally {
    submitting.value = false;
  }
}

// —— 报告态:简版轮询(2s/终态停/卸载清;P5-21 RunView 先例的页内简版,暂不抽公共 composable) ——

const run = ref<RunState | null>(null);
/** 404/403:运行不可见(不存在/非属主),轮询终止 */
const loadError = ref('');
const retrying = ref(false);
/** 提交上下文(勾选 nodeIds):路由 state 带入,重试复用;刷新丢失则禁用重试(§8.7 禁用带原因) */
const launch = ref<SummarizePayload | null>(null);

/** 结果页出处联动状态(citations 角标 → 清单行高亮,2s 回落) */
const cited = ref<number | null>(null);
let citeTimer: number | undefined;
let timer: number | undefined;

function readLaunch(): SummarizePayload | null {
  const raw = (router.options.history.state as Record<string, unknown> | undefined)?.keSummary;
  if (typeof raw !== 'string') return null;
  try {
    const parsed = JSON.parse(raw) as SummarizePayload;
    return parsed !== null && Array.isArray(parsed.nodeIds) && parsed.nodeIds.length > 0 ? parsed : null;
  } catch {
    return null;
  }
}

const phase = computed<'loading' | 'running' | 'done' | 'failed' | 'quota' | 'missing'>(() => {
  if (quotaMsg.value) return 'quota';
  if (loadError.value) return 'missing';
  if (run.value === null) return 'loading';
  if (run.value.status === 'DONE') return 'done';
  if (isTerminal(run.value.status)) return 'failed';
  return 'running';
});

/** 原因行:error 文案优先,TIMEOUT/FAILED 各有回落(与 RunView 同则) */
const reasonLine = computed(() => {
  const r = run.value;
  if (r?.error) return r.error;
  return r?.status === 'TIMEOUT' ? '任务超时,请稍后重试' : '服务繁忙,请稍后重试';
});

// —— 探索报告渲染(= ReportResult content_json:扁平键 + sources/disclaimer/audit) ——

const artifact = computed(() => run.value?.artifact ?? null);
/** DONE 可渲染=REPORT 形状(无 type 的 legacy DONE 走简单兜底卡) */
const resultReady = computed(() => artifact.value?.type === 'REPORT');
const disclaimer = computed(() => artifact.value?.disclaimer ?? '');
const strippedCount = computed(() => artifact.value?.audit?.stripped ?? 0);
const reportQuestions = computed(() => artifact.value?.openQuestions ?? []);
const branchRows = computed(() => artifact.value?.branchView ?? []);

/** sources map(assetId→检索文本)→ 有序出处行(key=assetId,序号即 [n] 角标编号) */
const sourceEntries = computed(() =>
  Object.entries(artifact.value?.sources ?? {}).map(([assetId, text]) => ({
    assetId: Number(assetId),
    text
  }))
);

const sourceRows = computed(() =>
  sourceEntries.value.map((e, i) => ({
    index: i + 1,
    assetId: e.assetId,
    snippet: e.text.length > SNIPPET_MAX ? `${e.text.slice(0, SNIPPET_MAX)}…` : e.text
  }))
);

function citeNo(assetId: number): number {
  return sourceEntries.value.findIndex((e) => e.assetId === assetId) + 1;
}

function citeTitle(assetId: number): string {
  const text = sourceEntries.value.find((e) => e.assetId === assetId)?.text ?? '';
  return text.slice(0, CITE_TITLE_MAX);
}

interface DecoratedFinding {
  body: string;
  claim: 'fact' | 'synth' | 'gen';
  cites: Array<{ assetId: number; no: number; title: string }>;
}

/** 关键发现装饰:claimType→ClaimBadge 三档(未知值安全降级 gen);citations 只留清单内的 assetId */
const findings = computed<DecoratedFinding[]>(() =>
  (artifact.value?.keyFindings ?? [])
    .filter((f) => f != null && typeof f.body === 'string')
    .map((f) => ({
      body: f.body,
      claim: f.claimType === 'FACT' ? 'fact' : f.claimType === 'SYNTHESIS' ? 'synth' : 'gen',
      cites: (f.citations ?? [])
        .map((assetId) => ({ assetId, no: citeNo(assetId), title: citeTitle(assetId) }))
        .filter((c) => c.no > 0)
    }))
);

// —— 出处联动(P5-22 同模式):点角标 → 清单对应行高亮,2s 回落 ——

const srcWrap = ref<HTMLElement | null>(null);

function onCite(no: number): void {
  cited.value = no;
  window.clearTimeout(citeTimer);
  citeTimer = window.setTimeout(() => {
    cited.value = null;
  }, HIGHLIGHT_MS);
  void nextTick(() => {
    srcWrap.value?.querySelector('.row-hl')?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  });
}

// —— 未决问题区(FR-E09):会话全部遗留疑问,静态展示 + 去追问 ——

/** 展示条件:有数据且主体已就绪(勾选态树已载入 / 报告态 DONE),运行/错误中不添扰 */
const showOqs = computed(
  () =>
    oqs.value.length > 0 &&
    !treeLoading.value &&
    !treeError.value &&
    (activeRunId.value == null
      ? tree.value !== null
      : phase.value === 'done' && resultReady.value)
);

/**
 * 去追问(review P5-FIX):异步 fetchRun 取该 run 的 submitContext 投影 → 组装 keRun 提交
 * 上下文随 state 跳 /runs/{runId} —— 落地即追问可用(RunView 不必等重建);submitContext 不完整
 * (旧行/无会话 legacy)或 fetchRun 失败(404/网络)→ toast「暂时无法追问」,停留本页。
 */
async function goAsk(runId: number): Promise<void> {
  try {
    const ctx = (await fetchRun(runId)).submitContext;
    if (!ctx || ctx.question == null || ctx.sessionId == null || ctx.nodeId == null) {
      showToast('暂时无法追问');
      return;
    }
    const payload: RunSubmitPayload = {
      cardVersionId: ctx.cardVersionId,
      sessionId: ctx.sessionId,
      nodeId: ctx.nodeId,
      question: ctx.question,
      // 档位回落会话档位记忆(submitContext 投影不含 level,与 RunView 重建同则)
      level: sessionStore.explainLevel
    };
    await router.push({
      path: `/runs/${runId}`,
      state: { keRun: JSON.stringify(payload) }
    });
  } catch {
    showToast('暂时无法追问');
  }
}

// —— 轮询与状态切换 ——

async function poll(): Promise<void> {
  const id = activeRunId.value;
  if (id == null) return;
  try {
    const state = await fetchRun(id);
    run.value = state;
    if (isTerminal(state.status)) {
      stopPolling();
      if (state.status === 'DONE') showToast('报告已生成');
    }
  } catch (e) {
    if (e instanceof ApiError && (e.code === 404 || e.code === 403)) {
      loadError.value = '运行不存在或无权访问';
      stopPolling();
    }
  }
}

function stopPolling(): void {
  if (timer !== undefined) {
    window.clearInterval(timer);
    timer = undefined;
  }
}

function startPolling(): void {
  stopPolling();
  void poll();
  timer = window.setInterval(() => {
    void poll();
  }, POLL_MS);
}

/** 切换到报告态(提交/重试共用):带 state 跳转 + 复位轮询与联动状态 */
async function enterRun(nextId: number, payload: SummarizePayload): Promise<void> {
  await router.push({
    path: '/summary',
    query: { sessionId: String(payload.sessionId), runId: String(nextId) },
    state: { keSummary: JSON.stringify(payload) }
  });
  activeRunId.value = nextId;
  launch.value = payload;
  run.value = null;
  loadError.value = '';
  quotaMsg.value = '';
  cited.value = null;
  startPolling();
}

/** FAILED/TIMEOUT:同一勾选 payload 重新 summarize → 429 页内文案,其余 toast;成功换新 run 续拍 */
async function retry(): Promise<void> {
  if (retrying.value || launch.value === null) return;
  retrying.value = true;
  try {
    const { runId: nextId } = await summarize(launch.value);
    await enterRun(nextId, launch.value);
  } catch (e) {
    if (e instanceof ApiError && e.code === 429) {
      quotaMsg.value = e.message;
    } else {
      showToast(e instanceof ApiError ? e.message : '操作失败,请稍后重试');
    }
  } finally {
    retrying.value = false;
  }
}

/** 错误/配额卡返回:回同会话勾选态(树未载入则补载),无会话上下文兜底回首页 */
async function backToPick(): Promise<void> {
  stopPolling();
  activeRunId.value = null;
  run.value = null;
  launch.value = null;
  loadError.value = '';
  quotaMsg.value = '';
  if (sessionId.value != null) {
    await router.replace({ path: '/summary', query: { sessionId: String(sessionId.value) } });
    if (tree.value === null && !treeLoading.value && treeError.value === '') {
      void loadTree(sessionId.value);
    }
  } else {
    void router.replace('/home');
  }
}

onMounted(() => {
  const sid = numericQuery('sessionId');
  if (sid == null) {
    // 契约:整理页必带会话(路径页必有会话);脏链接回路径页
    void router.replace('/path');
    return;
  }
  sessionId.value = sid;
  void fetchOpenQuestions(sid)
    .then((r) => {
      oqs.value = r.questions;
    })
    .catch(() => {}); // 附属卡静默失败
  const rid = numericQuery('runId');
  if (rid != null) {
    activeRunId.value = rid;
    launch.value = readLaunch();
    startPolling();
    return;
  }
  void loadTree(sid);
});

onBeforeUnmount(() => {
  stopPolling();
  window.clearTimeout(citeTimer);
});
</script>

<template>
  <div class="page">
    <TopBar section="整理发现" />

    <!-- 勾选态 -->
    <main
      v-if="activeRunId == null"
      class="wrap"
    >
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
            整理发现
          </h1>
          <p class="head-s">
            勾选这条路上你想收进报告的节点，跨分支也可以
          </p>
          <button
            type="button"
            class="sel-all"
            :disabled="allSelected"
            @click="selectAll"
          >
            全选
          </button>
          <p
            v-if="quotaMsg"
            class="quota-err"
            role="alert"
          >
            {{ quotaMsg }}
          </p>
        </header>

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

        <!-- 未决问题区(FR-E09):会话遗留疑问,静态展示 + 去追问 -->
        <section
          v-if="showOqs"
          class="oq"
        >
          <b class="oq-t">
            还有哪些疑问
          </b>
          <div
            v-for="q in oqs"
            :key="`${q.runId}-${q.question}`"
            class="oq-row"
          >
            <div class="oq-main">
              <span class="oq-q">
                {{ q.question }}
              </span>
              <span class="oq-time">
                {{ formatShortDateTime(q.collectedAt) }}
              </span>
            </div>
            <button
              type="button"
              class="oq-go"
              @click="goAsk(q.runId)"
            >
              去追问
            </button>
          </div>
        </section>
      </template>
    </main>

    <!-- 报告态:运行中(含首轮加载) -->
    <main
      v-else-if="phase === 'loading' || phase === 'running'"
      class="wrap center"
    >
      <div
        class="run-spin"
        role="status"
        aria-label="整理任务执行中"
      />
      <p class="run-q">
        整理中
      </p>
      <p class="note">
        正在把你勾选的节点汇总为一份探索报告;单次任务限时 60 秒,超时会自动停止
      </p>
    </main>

    <!-- 报告态 DONE:探索报告(04 §7.2 报告形态) -->
    <main
      v-else-if="phase === 'done' && resultReady"
      class="wrap report-wrap"
    >
      <h1 class="r-title">
        探索报告
      </h1>

      <section
        v-if="branchRows.length"
        class="block"
      >
        <b class="bk-t">
          分支视图
        </b>
        <div
          v-for="row in branchRows"
          :key="row.rootNodeTitle"
          class="bv-row"
        >
          <span class="bv-root">
            {{ row.rootNodeTitle }}
          </span>
          <span
            v-for="t in row.nodeTitles"
            :key="t"
            class="bv-node"
          >
            {{ t }}
          </span>
        </div>
      </section>

      <section
        v-if="findings.length"
        class="block"
      >
        <b class="bk-t">
          关键发现
        </b>
        <p
          v-for="(f, i) in findings"
          :key="i"
          class="finding"
        >
          {{ f.body }}
          <button
            v-for="c in f.cites"
            :key="c.assetId"
            type="button"
            class="cite"
            :title="c.title"
            @click="onCite(c.no)"
          >
            [{{ c.no }}]
          </button>
          <ClaimBadge
            class="badge"
            :type="f.claim"
          />
        </p>
      </section>

      <section
        v-if="reportQuestions.length"
        class="block"
      >
        <b class="bk-t">
          未决疑问
        </b>
        <p
          v-for="q in reportQuestions"
          :key="q"
          class="r-q"
        >
          <KeIcon
            class="rq-ic"
            name="clock"
          />
          {{ q }}
        </p>
      </section>

      <section
        ref="srcWrap"
        class="src"
      >
        <b>出处清单</b>
        <p
          v-for="row in sourceRows"
          :key="row.assetId"
          class="row"
          :class="{ 'row-hl': cited === row.index }"
        >
          [{{ row.index }}] {{ row.snippet }}
        </p>
        <p
          v-if="strippedCount > 0"
          class="warn"
        >
          引用校验:已剥离 {{ strippedCount }} 个无效引用
        </p>
      </section>
      <p
        v-if="disclaimer"
        class="foot"
      >
        {{ disclaimer }}
      </p>

      <!-- 未决问题区(FR-E09) -->
      <section
        v-if="showOqs"
        class="oq"
      >
        <b class="oq-t">
          还有哪些疑问
        </b>
        <div
          v-for="q in oqs"
          :key="`${q.runId}-${q.question}`"
          class="oq-row"
        >
          <div class="oq-main">
            <span class="oq-q">
              {{ q.question }}
            </span>
            <span class="oq-time">
              {{ formatShortDateTime(q.collectedAt) }}
            </span>
          </div>
          <button
            type="button"
            class="oq-go"
            @click="goAsk(q.runId)"
          >
            去追问
          </button>
        </div>
      </section>
    </main>

    <!-- DONE 无 REPORT(legacy):简单成功卡兜底 -->
    <main
      v-else-if="phase === 'done'"
      class="wrap center"
    >
      <div class="err-card">
        <span class="ok-ic">
          <KeIcon name="check" />
        </span>
        <b class="err-t">
          整理已完成
        </b>
        <span class="err-reason">
          本次整理没有产出报告内容,请重新勾选发起
        </span>
        <button
          type="button"
          class="err-alt"
          @click="backToPick"
        >
          返回整理
        </button>
      </div>
    </main>

    <!-- FAILED/TIMEOUT:错误卡(04 §8.4 模板)+ 重试 -->
    <main
      v-else-if="phase === 'failed'"
      class="wrap center"
    >
      <div class="err-card">
        <KeIcon
          class="err-ic"
          name="alert"
        />
        <b class="err-t">
          整理没有成功,你的路径不受影响
        </b>
        <span class="err-reason">
          {{ reasonLine }}
        </span>
        <button
          type="button"
          class="err-retry"
          :disabled="retrying || launch === null"
          :title="launch ? undefined : '勾选参数已丢失,请返回重新发起'"
          @click="retry"
        >
          重试
        </button>
        <button
          type="button"
          class="err-alt"
          @click="backToPick"
        >
          返回整理
        </button>
      </div>
    </main>

    <!-- 429:配额 envelope 文案页内展示(04 §8.6) -->
    <main
      v-else-if="phase === 'quota'"
      class="wrap center"
    >
      <div class="err-card">
        <KeIcon
          class="err-ic"
          name="alert"
        />
        <b class="err-t">
          {{ quotaMsg }}
        </b>
        <span class="err-reason">
          明早 8 点恢复,今天先看看别的吧
        </span>
        <button
          type="button"
          class="err-alt"
          @click="backToPick"
        >
          返回整理
        </button>
      </div>
    </main>

    <!-- 404/403:运行不存在或非属主 -->
    <main
      v-else
      class="wrap center"
    >
      <div class="err-card">
        <KeIcon
          class="err-ic"
          name="alert"
        />
        <b class="err-t">
          运行不存在或无权访问
        </b>
        <span class="err-reason">
          它可能已被清理,或不是你的探索任务
        </span>
        <button
          type="button"
          class="err-alt"
          @click="backToPick"
        >
          返回整理
        </button>
      </div>
    </main>

    <!-- 勾选态底部固定条:已选计数 + 提交 -->
    <div
      v-if="activeRunId == null && tree !== null && !treeError"
      class="sum-bar"
    >
      <span class="sel-count">
        已选 {{ checkedCount }} 个节点
      </span>
      <button
        type="button"
        class="sum-go"
        :disabled="submitting || checkedCount === 0"
        @click="submit"
      >
        生成探索报告
      </button>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; padding: 52px 16px 120px; background: var(--ke-bg); }
.wrap { margin: 14px auto 0; max-width: 480px; }
.center { max-width: 340px; margin-top: 46px; text-align: center; }
.state { margin: 40px 0 0; text-align: center; font-size: 12px; color: var(--ke-sub); }

/* 勾选态头部 */
.head-t { margin: 0; font-family: var(--ke-font-display); font-size: 20px; font-weight: 900; color: var(--ke-ink); }
.head-s { margin: 6px 0 0; font-size: 12px; line-height: 1.7; color: var(--ke-sub); }
.sel-all { display: inline-block; margin-top: 10px; padding: 4px 14px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-full); background: var(--ke-surface); color: var(--ke-ink); font-size: 12px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; }
.sel-all:disabled { opacity: 0.45; cursor: default; }
.quota-err { margin: 10px 0 0; padding: 8px 12px; border-radius: var(--ke-radius-m); background: var(--ke-gen-soft); color: var(--ke-warn); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }

/* 分支分组:根行=分支标题,子孙按深度缩进(padding-left 内联注入) */
.branch { margin-top: 10px; padding: 4px 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.b-row, .n-row { display: flex; align-items: baseline; gap: 9px; padding: 8px 0; font-size: 13px; line-height: 1.6; color: var(--ke-ink); cursor: pointer; }
.n-row + .n-row, .b-row + .n-row { border-top: 1px solid var(--ke-line-2); }
.b-t { font-weight: 700; }
.ck { flex-shrink: 0; width: 15px; height: 15px; margin: 0; accent-color: var(--ke-primary); cursor: pointer; }

/* 未决问题区(FR-E09) */
.oq { margin-top: 16px; padding: 12px 13px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.oq-t { font-size: 13px; font-weight: 700; color: var(--ke-ink); }
.oq-row { display: flex; align-items: center; gap: 10px; margin-top: 8px; padding: 8px 10px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); background: var(--ke-surface-2); }
.oq-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.oq-q { font-size: 13px; line-height: 1.6; color: var(--ke-ink); overflow-wrap: anywhere; text-align: left; }
.oq-time { font-size: 11px; color: var(--ke-sub-2); }
.oq-go { flex-shrink: 0; padding: 6px 12px; border: none; border-radius: var(--ke-radius-m); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 12px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; }

/* 底部固定条:已选计数 + 提交 */
.sum-bar { position: fixed; left: 0; right: 0; bottom: 0; z-index: var(--ke-z-bar); display: flex; align-items: center; gap: 10px; padding: 10px 12px calc(10px + env(safe-area-inset-bottom, 0px)); background: var(--ke-surface); border-top: 1px solid var(--ke-line); }
.sel-count { flex: 1; min-width: 0; font-size: 12px; color: var(--ke-sub); }
.sum-go { flex-shrink: 0; padding: 10px 22px; border: none; border-radius: var(--ke-radius-m); background: var(--ke-primary); color: var(--ke-white); font-size: 13px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; transition: transform var(--ke-dur-fast) var(--ke-ease); }
.sum-go:active { transform: scale(0.97); }
.sum-go:disabled { opacity: 0.45; cursor: default; transform: none; }

/* 报告态:spinner + 探索报告(04 §7.2) */
.run-spin { display: inline-block; width: 44px; height: 44px; border: 3px solid var(--ke-line-2); border-top-color: var(--ke-primary); border-radius: var(--ke-radius-full); animation: ke-sum-spin 0.9s linear infinite; }

@keyframes ke-sum-spin { to { transform: rotate(360deg); } }
.run-q { margin: 18px 0 0; font-family: var(--ke-font-display); font-size: 15px; font-weight: 700; color: var(--ke-ink); }
.note { margin: 20px 0 0; font-size: 12px; line-height: 1.8; color: var(--ke-sub-2); }

.report-wrap { padding-bottom: 24px; }
.r-title { margin: 8px 0 0; font-family: var(--ke-font-display); font-size: 20px; font-weight: 900; color: var(--ke-ink); }
.block { margin-top: 16px; padding: 12px 13px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.bk-t { font-size: 13px; font-weight: 700; color: var(--ke-ink); }

.bv-row { display: flex; flex-direction: column; gap: 3px; margin-top: 9px; padding: 8px 10px; border: 1px solid var(--ke-line-2); border-radius: var(--ke-radius-m); background: var(--ke-surface-2); }
.bv-root { font-size: 13px; font-weight: 700; color: var(--ke-ink); }
.bv-node { padding-left: 14px; font-size: 12px; line-height: 1.6; color: var(--ke-sub); }

.finding { margin: 10px 0 0; font-size: 14px; line-height: 1.8; text-align: justify; color: var(--ke-ink); }
.cite { margin: 0 1px; padding: 0 2px; border: none; background: none; color: var(--ke-primary); font-size: 11px; font-weight: 700; vertical-align: super; cursor: pointer; }
.cite:hover { text-decoration: underline; }
.badge { margin-left: 6px; vertical-align: 1px; }

.r-q { display: flex; align-items: baseline; gap: 6px; margin: 9px 0 0; font-size: 13px; line-height: 1.7; color: var(--ke-ink); overflow-wrap: anywhere; }
.rq-ic { flex-shrink: 0; width: 13px; height: 13px; color: var(--ke-primary); }

.src { margin-top: 16px; background: var(--ke-surface-2); border: 1px solid var(--ke-line); border-radius: var(--ke-radius-s); padding: 10px 13px; font-size: 12px; color: var(--ke-sub); line-height: 1.85; }
.src b { color: var(--ke-ink); }
.row { margin: 0; overflow-wrap: anywhere; transition: background var(--ke-dur-fast) var(--ke-ease), transform var(--ke-dur-fast) var(--ke-ease); }
.row.row-hl { margin: 0 -4px; padding: 0 4px; border-radius: var(--ke-radius-xs); background: var(--ke-primary-soft); color: var(--ke-ink); transform: scale(1.03); transform-origin: left center; }
.warn { display: flex; align-items: baseline; gap: 4px; margin: 4px 0 0; color: var(--ke-warn); }
.foot { margin: 14px 0 0; font-size: 11px; line-height: 1.7; color: var(--ke-sub-2); }

/* 错误/兜底卡(KCard 族) */
.err-card { margin-top: 8px; padding: 26px 18px 22px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.err-ic { display: inline-flex; width: 40px; height: 40px; align-items: center; justify-content: center; border-radius: var(--ke-radius-full); background: var(--ke-surface-2); color: var(--ke-sub-2); }
.err-ic .ke-icon { width: 22px; height: 22px; }
.ok-ic { display: inline-flex; width: 40px; height: 40px; align-items: center; justify-content: center; border-radius: var(--ke-radius-full); background: var(--ke-fact-soft); color: var(--ke-fact); }
.ok-ic .ke-icon { width: 22px; height: 22px; }
.err-t { display: block; margin-top: 12px; font-size: 14px; font-weight: 600; line-height: 1.6; color: var(--ke-ink); }
.err-reason { display: block; margin-top: 6px; font-size: 12px; line-height: 1.7; color: var(--ke-sub); overflow-wrap: anywhere; }
.err-retry, .err-alt { display: block; width: 100%; margin-top: 12px; padding: 10px 0; border-radius: var(--ke-radius-m); font-size: 13px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; box-sizing: border-box; }
.err-retry { border: none; background: var(--ke-primary-soft); color: var(--ke-primary); }
.err-retry:disabled { opacity: 0.45; cursor: default; }
.err-alt { border: 1px solid var(--ke-line-strong); background: var(--ke-surface); color: var(--ke-ink); }

/* 树加载失败空态 */
.load-err { margin: 60px auto 0; max-width: 320px; text-align: center; }
.le-ic { width: 40px; height: 40px; color: var(--ke-sub-2); }
.le-t { display: block; margin-top: 10px; font-size: 14px; font-weight: 600; color: var(--ke-ink); }
.le-s { display: block; margin-top: 4px; font-size: 12px; color: var(--ke-sub); overflow-wrap: anywhere; }
</style>
