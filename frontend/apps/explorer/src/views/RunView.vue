<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showToast } from 'vant';
import { ClaimBadge, KeIcon } from '@ke/shared';
import { ApiError } from '../api/http';
import { fetchRun, isTerminal, submitRun } from '../api/runs';
import type { RunState, RunSubmitPayload } from '../api/runs';
import type { ExplainLevel } from '../api/sessions';
import AskBar from '../components/AskBar.vue';
import CompareCard from '../components/CardRenderer/CompareCard.vue';
import TopBar from '../components/TopBar.vue';
import { useSessionStore } from '../stores/sessionStore';

/**
 * 执行态页 + 讲解/比较结果页(04 §7.2 RunProgress/ExplainResult,FR-S04/E08/E10/E11 界面):
 * 运行中为 spinner + 任务问题 + 三步清单 + 限时说明,2s 轮询 GET /runs/{id}(隐藏暂停、
 * 终态即停、404/403 终止);DONE 且有 artifact 时同路由原地渲染结果页 ——
 * 讲解(EXPLAIN,artifact.output):chips(讲解卡 · 由智能体生成 + 档位)→ 宋体问题标题 →
 * summary 引言段 → 分段正文(14/1.8 两端对齐,段尾 ClaimBadge 三档 + citations 角标)→
 * 出处清单(sources map,audit.stripped/证据缺口警示行)→「还可以继续问」→ 受控生成声明脚注;
 * 比较(COMPARE,Task 23:artifact.type==='COMPARE_CARD'):chips(对比卡 · 由智能体生成)→
 * 问题标题 → CompareCard 维度×对象表格(citations 角标联动出处清单)→ 复用出处清单/脚注。
 * 底部 AskBar 常驻追问(payload 从路由 state 继承提交上下文,parentRunId 记追问链;刷新丢
 * state 后由 GET /runs/{id} 的 submitContext 投影原地重建,review P5-FIX——两者皆无才禁用,
 * §8.7 禁用带原因;追问恒走讲解通道)。档位 chip 点击循环切换(PUT explain-level,
 * 下次讲解生效)。停顿三键不入结果页:经 TopBar 指南针去路径页(FR-E09「还有哪些疑问」=
 * openQuestions,「换个方向」=路径页分叉,Task 22 授权决策)。question/重试 payload 经路由
 * state(keRun)携带;FAILED/TIMEOUT 按错误卡模板给「重试/换个问法」;429 以页内错误态展示 envelope 文案。
 */
const POLL_MS = 2000;
const STEPS = ['读取上下文', '检索资料', '生成讲解并校验出处'] as const;
/** 出处清单行文本截断(sources map 快照文本 → 行 snippet) */
const SNIPPET_MAX = 30;
/** 角标 title 的出处文本缩略(Task 22:首 12 字) */
const CITE_TITLE_MAX = 12;
const HIGHLIGHT_MS = 2000;
/** FR-E10 档位三档(PathView 同 label;结果页 chip 点击循环切换) */
const LEVELS: ReadonlyArray<{ value: ExplainLevel; label: string }> = [
  { value: 'SIMPLE', label: '简明' },
  { value: 'DEEP', label: '深入' },
  { value: 'CHILD', label: '儿童' }
];

const route = useRoute();
const router = useRouter();
const sessionStore = useSessionStore();

const run = ref<RunState | null>(null);
/** 404/403:运行不可见(不存在/非属主),轮询终止 */
const loadError = ref('');
/** 重试遇 429:配额 envelope 文案以页内错误态展示(04 §8.6) */
const quotaMsg = ref('');
const retrying = ref(false);
/** 提交上下文(含 question):路由 state 带入;刷新丢 state 后由 submitContext 重建(review P5-FIX)。
 *  重试/追问复用同一上下文 */
const launch = ref<RunSubmitPayload | null>(null);

/** 结果页追问/联动状态 */
const cited = ref<number | null>(null);
const askDraft = ref('');
const asking = ref(false);
const switching = ref(false);
let citeTimer: number | undefined;

let timer: number | undefined;

function readLaunch(): RunSubmitPayload | null {
  const raw = (router.options.history.state as Record<string, unknown> | undefined)?.keRun;
  if (typeof raw !== 'string') return null;
  try {
    const parsed = JSON.parse(raw) as RunSubmitPayload;
    return parsed !== null && typeof parsed.question === 'string' ? parsed : null;
  } catch {
    return null;
  }
}

const question = computed(() => launch.value?.question ?? '智能服务执行中');

/**
 * 刷新丢路由 state 后的提交上下文重建(review P5-FIX):用 GET /runs/{id} 的 submitContext
 * 投影(属主可见)拼回 launch——question/cardVersionId/sessionId/nodeId 缺一不可,level 回落
 * 会话档位记忆(投影不含 level);serviceType 仅 COMPARE 显式带上(Phase 5 终审 rider:FAILED
 * 比较运行刷新后重试保留比较通道,缺键=EXPLAIN 缺省故讲解/追问无需映射,追问仍恒走讲解)。
 * 重建后追问/重试与路由 state 带入同语义;submitContext 也 null(非属主不可达/旧行异常)
 * → launch 维持 null,AskBar/重试按禁用文案展示。
 */
function rebuildLaunch(state: RunState): void {
  const ctx = state.submitContext;
  if (launch.value !== null || !ctx || ctx.question == null || ctx.sessionId == null || ctx.nodeId == null) {
    return;
  }
  launch.value = {
    cardVersionId: ctx.cardVersionId,
    sessionId: ctx.sessionId,
    nodeId: ctx.nodeId,
    question: ctx.question,
    level: sessionStore.explainLevel,
    ...(ctx.serviceType === 'COMPARE' ? { serviceType: 'COMPARE' as const } : {})
  };
}

const phase = computed<'loading' | 'running' | 'done' | 'failed' | 'quota' | 'missing'>(() => {
  if (quotaMsg.value) return 'quota';
  if (loadError.value) return 'missing';
  if (run.value === null) return 'loading';
  if (run.value.status === 'DONE') return 'done';
  if (isTerminal(run.value.status)) return 'failed';
  return 'running';
});

/** 分步清单(静态三行):运行中前两步 ✓ 第三步 ◐;DONE 全 ✓(错误卡替换整块,不渲染) */
const stepStates = computed<Array<'done' | 'doing'>>(() =>
  run.value?.status === 'DONE' ? ['done', 'done', 'done'] : ['done', 'done', 'doing']
);

/** 原因行(04 §8.4「超时/网络/服务繁忙」):error 文案优先,TIMEOUT/FAILED 各有回落 */
const reasonLine = computed(() => {
  const r = run.value;
  if (r?.error) return r.error;
  return r?.status === 'TIMEOUT' ? '任务超时,请稍后重试' : '服务繁忙,请稍后重试';
});

// —— 讲解/比较结果页(P5-21 实测形状;全键 optional 容 non_null 缺省) ——
// Task 23 分流:artifact.type==='COMPARE_CARD' → 比较结果(CompareCard 表格 + 复用出处清单/脚注);
// 其余(output 存在)→ 讲解结果页。

const output = computed(() => run.value?.artifact?.output ?? null);
/** 比较结果(artifact.type 分流,后端 COMPARE_CARD content_json 专属键) */
const compareData = computed(() => run.value?.artifact?.data ?? null);
const isCompare = computed(() => run.value?.artifact?.type === 'COMPARE_CARD' && compareData.value !== null);
/** 结果页可渲染=DONE 且(讲解 output | 比较 data)存在(无 artifact 的 legacy DONE 走简单成功卡兜底) */
const resultReady = computed(() => output.value !== null || compareData.value !== null);

const disclaimer = computed(() => run.value?.artifact?.disclaimer ?? '');
const strippedCount = computed(() => run.value?.artifact?.audit?.stripped ?? 0);
const evidenceGaps = computed(() => output.value?.evidenceGaps ?? []);
const openQuestions = computed(() => output.value?.openQuestions ?? []);

/** sources map(assetId→检索文本)→ 有序出处行(key=assetId,序号即 [n] 角标编号) */
const sourceEntries = computed(() =>
  Object.entries(run.value?.artifact?.sources ?? {}).map(([assetId, text]) => ({
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

interface DecoratedSection {
  body: string;
  claim: 'fact' | 'synth' | 'gen';
  cites: Array<{ assetId: number; no: number; title: string }>;
}

/** 段落装饰:claimType→ClaimBadge 三档(未知值安全降级 gen);citations 只留清单内的 assetId */
const decorated = computed<DecoratedSection[]>(() =>
  (output.value?.sections ?? [])
    .filter((s): s is NonNullable<typeof s> => s != null && typeof s.body === 'string')
    .map((s) => ({
      body: s.body,
      claim: s.claimType === 'FACT' ? 'fact' : s.claimType === 'SYNTHESIS' ? 'synth' : 'gen',
      cites: (s.citations ?? [])
        .map((assetId) => ({ assetId, no: citeNo(assetId), title: citeTitle(assetId) }))
        .filter((c) => c.no > 0)
    }))
);

const levelLabel = computed(
  () => LEVELS.find((l) => l.value === sessionStore.explainLevel)?.label ?? '简明'
);

/** 追问可用=提交上下文仍在(路由 state 或刷新后经 submitContext 重建);两者皆无才禁用(§8.7 禁用带原因) */
const canAsk = computed(() => launch.value !== null);
const askPlaceholder = computed(() =>
  canAsk.value ? '针对这次讲解,继续问一句…' : '刷新后无法追问,请从卡片重新发起'
);

// —— 出处联动(P3-15 同模式):点角标 → 清单对应行高亮,2s 回落 ——

const srcWrap = ref<HTMLElement | null>(null);
/** 页根:追问输入框预填聚焦的查询范围(AskBar 常驻底部,同树内) */
const pageEl = ref<HTMLElement | null>(null);

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

/** 比较结果角标(Task 23):CompareCard emit 的 citations 是 assetId → 换算出处清单序号后联动;
 *  清单外的 id(理论上已被后端 sanitize 剥离)静默忽略 */
function onCompareCite(assetId: number): void {
  const no = citeNo(assetId);
  if (no > 0) onCite(no);
}

/** 还可以继续问:点击预填追问输入框并聚焦 */
function prefill(q: string): void {
  askDraft.value = q;
  void nextTick(() => {
    pageEl.value?.querySelector<HTMLInputElement>('.ask-in')?.focus();
  });
}

// —— 档位切换(FR-E10):chip 点击循环三档,PUT 会话档位,下次讲解生效 ——

async function cycleLevel(): Promise<void> {
  if (switching.value) return;
  const idx = LEVELS.findIndex((l) => l.value === sessionStore.explainLevel);
  const next = LEVELS[(idx + 1) % LEVELS.length]!;
  switching.value = true;
  try {
    await sessionStore.changeExplainLevel(next.value);
    showToast('已切换,下次讲解生效');
  } catch (e) {
    showToast(e instanceof ApiError ? e.message : '操作失败,请稍后重试');
  } finally {
    switching.value = false;
  }
}

// —— 轮询:挂载即首轮 + 2s 间隔;终态/不可见即停,404/403 终止,网络抖动静默续拍 ——

async function poll(): Promise<void> {
  try {
    const state = await fetchRun(Number(route.params.id));
    run.value = state;
    // 刷新直达(无路由 state):首轮响应即经 submitContext 重建提交上下文(幂等,已有 launch 跳过)
    rebuildLaunch(state);
    if (isTerminal(state.status)) {
      stopPolling();
      // 结果落地提示(任务简报:轮询结果落地后 toast 通知)
      if (state.status === 'DONE') showToast('讲解已生成');
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

function onVisibility(): void {
  if (document.visibilityState === 'hidden') {
    stopPolling();
  } else if (run.value !== null && !isTerminal(run.value.status) && !loadError.value && !quotaMsg.value) {
    startPolling();
  }
}

onMounted(() => {
  // 路由 state 优先;刷新丢 state 由首轮 poll 的 submitContext 重建(rebuildLaunch)
  launch.value = readLaunch();
  startPolling();
  document.addEventListener('visibilitychange', onVisibility);
});

onBeforeUnmount(() => {
  stopPolling();
  window.clearTimeout(citeTimer);
  document.removeEventListener('visibilitychange', onVisibility);
});

// —— 失败重试:同一 payload 重新提交 → 429 页内错误态,其余 toast;成功换新 run 续拍 ——

async function retry(): Promise<void> {
  if (retrying.value || !launch.value) return;
  retrying.value = true;
  try {
    const { runId: nextId } = await submitRun(launch.value);
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

/** 追问发送(FR-E08):payload 继承路由 state 提交上下文 + parentRunId=当前 run → 跳新 run 续拍 */
async function sendAsk(text: string): Promise<void> {
  const base = launch.value;
  const current = run.value;
  if (asking.value || !base || !current) return;
  asking.value = true;
  try {
    const payload: RunSubmitPayload = {
      cardVersionId: base.cardVersionId,
      sessionId: base.sessionId,
      nodeId: base.nodeId,
      question: text,
      level: sessionStore.explainLevel,
      parentRunId: current.runId
    };
    const { runId } = await submitRun(payload);
    askDraft.value = '';
    await enterRun(runId, payload);
  } catch (e) {
    showToast(e instanceof ApiError ? e.message : '操作失败,请稍后重试');
  } finally {
    asking.value = false;
  }
}

/** 切换到新 run(重试/追问共用):带 state 跳转 + 复位轮询与联动状态 */
async function enterRun(nextId: number, payload: RunSubmitPayload): Promise<void> {
  await router.push({ path: `/runs/${nextId}`, state: { keRun: JSON.stringify(payload) } });
  launch.value = payload;
  run.value = null;
  loadError.value = '';
  quotaMsg.value = '';
  cited.value = null;
  startPolling();
}

/** 返回:有历史则 back(换个问法=回卡页),直达链接兜底回首页 */
function goBack(): void {
  if (window.history.state?.back == null) {
    void router.replace('/home');
  } else {
    router.back();
  }
}
</script>

<template>
  <div
    ref="pageEl"
    class="page"
  >
    <TopBar section="智能服务" />

    <!-- 运行中(含首轮加载):RunProgress -->
    <main
      v-if="phase === 'loading' || phase === 'running'"
      class="wrap"
    >
      <div
        class="run-spin"
        role="status"
        aria-label="任务执行中"
      />
      <p class="run-q">
        {{ question }}
      </p>
      <ol class="steps">
        <li
          v-for="(step, i) in STEPS"
          :key="step"
          class="step"
          :class="stepStates[i]"
        >
          <span
            class="st-ic"
            aria-hidden="true"
          >
            <KeIcon
              v-if="stepStates[i] === 'done'"
              name="check"
            />
            <template v-else>
              ◐
            </template>
          </span>
          <span class="st-t">
            {{ step }}
          </span>
        </li>
      </ol>
      <p class="note">
        单次任务限时 60 秒,超时会自动停止;你可以离开此页,路径不受影响
      </p>
    </main>

    <!-- DONE + artifact:结果页(04 §7.2;讲解=ExplainResult 卡形态,比较=对比卡表格,Task 23 分流) -->
    <main
      v-else-if="phase === 'done' && resultReady"
      class="wrap result-wrap"
    >
      <div class="chips">
        <span class="chip gen-tag">
          {{ isCompare ? '对比卡 · 由智能体生成' : '讲解卡 · 由智能体生成' }}
        </span>
        <button
          v-if="!isCompare"
          type="button"
          class="chip lvl"
          aria-label="讲解档位,点击切换"
          :disabled="switching"
          @click="cycleLevel"
        >
          {{ levelLabel }}档
        </button>
      </div>
      <h1 class="q-title">
        {{ question }}
      </h1>
      <!-- 比较:CompareCard 表格(citations 角标联动下方出处清单),Task 23 -->
      <CompareCard
        v-if="isCompare && compareData"
        :content="compareData"
        @cite="onCompareCite"
      />
      <template v-else>
        <p
          v-if="output?.summary"
          class="sum"
        >
          {{ output.summary }}
        </p>
        <p
          v-for="(s, i) in decorated"
          :key="i"
          class="para"
        >
          {{ s.body }}
          <button
            v-for="c in s.cites"
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
            :type="s.claim"
          />
        </p>
      </template>
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
        <p
          v-for="g in evidenceGaps"
          :key="g"
          class="warn"
        >
          <KeIcon
            class="w-ic"
            name="alert"
          />
          证据缺口:{{ g }}
        </p>
      </section>
      <section
        v-if="!isCompare && openQuestions.length"
        class="more"
      >
        <b class="more-t">
          还可以继续问
        </b>
        <button
          v-for="q in openQuestions"
          :key="q"
          type="button"
          class="more-q"
          @click="prefill(q)"
        >
          {{ q }}
        </button>
      </section>
      <p
        v-if="disclaimer"
        class="foot"
      >
        {{ disclaimer }}
      </p>
    </main>

    <!-- DONE 无 artifact(legacy):简单成功卡兜底,不渲染结果页与追问条 -->
    <main
      v-else-if="phase === 'done'"
      class="wrap"
    >
      <div class="done-card">
        <span class="done-ic">
          <KeIcon name="check" />
        </span>
        <b class="done-t">
          讲解已生成
        </b>
        <span class="done-s">
          本次讲解没有产出内容,请从卡片重新发起
        </span>
      </div>
    </main>

    <!-- FAILED/TIMEOUT:错误卡(04 §8.4 模板) -->
    <main
      v-else-if="phase === 'failed'"
      class="wrap"
    >
      <div class="err-card">
        <KeIcon
          class="err-ic"
          name="alert"
        />
        <b class="err-t">
          生成没有成功,你的路径不受影响
        </b>
        <span class="err-reason">
          {{ reasonLine }}
        </span>
        <button
          type="button"
          class="err-retry"
          :disabled="retrying || !launch"
          :title="launch ? undefined : '提交参数已丢失,请返回重新发起'"
          @click="retry"
        >
          重试
        </button>
        <button
          type="button"
          class="err-alt"
          @click="goBack"
        >
          换个问法
        </button>
      </div>
    </main>

    <!-- 429:配额 envelope 文案页内展示(04 §8.6) -->
    <main
      v-else-if="phase === 'quota'"
      class="wrap"
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
          @click="goBack"
        >
          返回
        </button>
      </div>
    </main>

    <!-- 404/403:运行不存在或非属主 -->
    <main
      v-else
      class="wrap"
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
          @click="goBack"
        >
          返回
        </button>
      </div>
    </main>

    <!-- 追问条:仅结果页常驻(AskBar 内部承载禁用与原因) -->
    <AskBar
      v-if="phase === 'done' && resultReady"
      v-model="askDraft"
      class="ask"
      :disabled="!canAsk"
      :busy="asking"
      :placeholder="askPlaceholder"
      @send="sendAsk"
    />
  </div>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; padding: 52px 16px 40px; background: var(--ke-bg); padding-top: 80px; padding-right: 24px; padding-left: 24px; }
.wrap { margin: 46px auto 0; max-width: 340px; text-align: center; }

/* spinner 44 / 3px 主色环(04 §7.2 RunProgress) */
.run-spin { display: inline-block; width: 44px; height: 44px; border: 3px solid var(--ke-line-2); border-top-color: var(--ke-primary); border-radius: var(--ke-radius-full); animation: ke-run-spin 0.9s linear infinite; }

@keyframes ke-run-spin { to { transform: rotate(360deg); } }

.run-q { margin: 18px 0 0; font-family: var(--ke-font-display); font-size: 15px; font-weight: 700; line-height: 1.5; color: var(--ke-ink); }
.steps { display: inline-block; margin: 22px 0 0; padding: 0; list-style: none; text-align: left; }
.step { display: flex; align-items: center; gap: 9px; margin-top: 12px; font-size: 13px; line-height: 1.6; color: var(--ke-sub); }
.step:first-child { margin-top: 0; }
.step.done { color: var(--ke-ink); }
.step.doing { color: var(--ke-primary); font-weight: 700; }
.st-ic { display: flex; width: 16px; justify-content: center; color: var(--ke-primary); font-size: 12px; }
.step.done .st-ic { color: var(--ke-ink); }
.st-ic .ke-icon { width: 14px; height: 14px; }
.note { margin: 26px 0 0; font-size: 12px; line-height: 1.8; color: var(--ke-sub-2); }

/* 错误卡(KCard 族:白底细线描边圆角 l) */
.err-card { margin-top: 8px; padding: 26px 18px 22px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.err-ic { display: inline-flex; width: 40px; height: 40px; align-items: center; justify-content: center; border-radius: var(--ke-radius-full); background: var(--ke-surface-2); color: var(--ke-sub-2); }
.err-ic .ke-icon { width: 22px; height: 22px; }
.err-t { display: block; margin-top: 12px; font-size: 14px; font-weight: 600; line-height: 1.6; color: var(--ke-ink); }
.err-reason { display: block; margin-top: 6px; font-size: 12px; line-height: 1.7; color: var(--ke-sub); overflow-wrap: anywhere; }
.err-retry, .err-alt { display: block; width: 100%; margin-top: 12px; padding: 10px 0; border-radius: var(--ke-radius-m); font-size: 13px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; box-sizing: border-box; }
.err-retry { border: none; background: var(--ke-primary-soft); color: var(--ke-primary); }
.err-retry:disabled { opacity: 0.45; cursor: default; }
.err-alt { border: 1px solid var(--ke-line-strong); background: var(--ke-surface); color: var(--ke-ink); }

/* DONE 无 artifact 兜底卡 */
.done-card { margin-top: 8px; padding: 26px 18px 22px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.done-ic { display: inline-flex; width: 40px; height: 40px; align-items: center; justify-content: center; border-radius: var(--ke-radius-full); background: var(--ke-fact-soft); color: var(--ke-fact); }
.done-ic .ke-icon { width: 22px; height: 22px; }
.done-t { display: block; margin-top: 12px; font-size: 14px; font-weight: 600; line-height: 1.6; color: var(--ke-ink); }
.done-s { display: block; margin-top: 6px; font-size: 12px; line-height: 1.7; color: var(--ke-sub); }

/* —— 讲解结果页(04 §7.2 讲解卡形态:chips→标题→正文段+徽标+角标→出处清单→继续问→脚注) —— */
.result-wrap { max-width: 480px; margin-top: 24px; padding-bottom: 88px; text-align: left; }
.chips { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.chip { display: inline-flex; align-items: center; gap: 3px; padding: 1px 9px; border: none; border-radius: var(--ke-radius-full); font-size: 11px; font-weight: 600; line-height: 1.7; }
.chip.gen-tag { background: var(--ke-gen-soft); color: var(--ke-gen); }
.chip.lvl { background: var(--ke-primary-soft); color: var(--ke-primary); font-family: var(--ke-font); cursor: pointer; }
.chip.lvl:disabled { opacity: 0.6; cursor: default; }
.q-title { margin: 12px 0 0; font-family: var(--ke-font-display); font-size: 18px; font-weight: 900; line-height: 1.45; color: var(--ke-ink); }
.sum { margin: 10px 0 0; padding: 2px 0 2px 10px; border-left: 3px solid var(--ke-primary); font-size: 14px; line-height: 1.8; color: var(--ke-sub); }
.para { margin: 12px 0 0; font-size: 14px; line-height: 1.8; text-align: justify; color: var(--ke-ink); }
.cite { margin: 0 1px; padding: 0 2px; border: none; background: none; color: var(--ke-primary); font-size: 11px; font-weight: 700; vertical-align: super; cursor: pointer; }
.cite:hover { text-decoration: underline; }
.badge { margin-left: 6px; vertical-align: 1px; }

.src { margin-top: 16px; background: var(--ke-surface-2); border: 1px solid var(--ke-line); border-radius: var(--ke-radius-s); padding: 10px 13px; font-size: 12px; color: var(--ke-sub); line-height: 1.85; }
.src b { color: var(--ke-ink); }
.row { margin: 0; overflow-wrap: anywhere; transition: background var(--ke-dur-fast) var(--ke-ease), transform var(--ke-dur-fast) var(--ke-ease); }
.row.row-hl { margin: 0 -4px; padding: 0 4px; border-radius: var(--ke-radius-xs); background: var(--ke-primary-soft); color: var(--ke-ink); transform: scale(1.03); transform-origin: left center; }
.warn { display: flex; align-items: baseline; gap: 4px; margin: 4px 0 0; color: var(--ke-warn); }
.w-ic { width: 12px; height: 12px; }

.more { margin-top: 16px; padding: 12px 13px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.more-t { font-size: 13px; font-weight: 700; color: var(--ke-ink); }
.more-q { display: block; width: 100%; margin-top: 8px; padding: 8px 10px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); background: var(--ke-surface-2); font-size: 13px; line-height: 1.6; color: var(--ke-ink); text-align: left; font-family: var(--ke-font); cursor: pointer; box-sizing: border-box; }
.more-q:active { background: var(--ke-primary-soft); }
.foot { margin: 14px 0 0; font-size: 11px; line-height: 1.7; color: var(--ke-sub-2); }
</style>
