<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showToast } from 'vant';
import { KeIcon } from '@ke/shared';
import { ApiError } from '../api/http';
import { fetchRun, isTerminal, submitRun } from '../api/runs';
import type { RunState, RunSubmitPayload } from '../api/runs';
import TopBar from '../components/TopBar.vue';

/**
 * 执行态页(04 §7.2 RunProgress + §8.3/8.4/8.6,FR-S04 界面):spinner 44 主色环 +
 * 任务问题 + 三步清单(读取上下文→检索资料→生成讲解并校验出处)+ 限时说明。
 * 2s 轮询 GET /runs/{id}:组件卸载停止、页面隐藏(document.visibilitychange)暂停、
 * 终态即停;禁止无限 spinner —— FAILED/TIMEOUT 按错误卡模板给「重试/换个问法」。
 * question 与重试 payload 经路由 history state(keRun)携带,刷新丢失走「智能服务执行中」
 * 兜底、重试置灰(§8.7 禁用带原因);429 重试以页内错误态展示 envelope 文案。
 * DONE 暂以简单成功卡承载(Task 22 结果页接入后替换),「查看讲解」入口先行置灰占位。
 */
const POLL_MS = 2000;
const STEPS = ['读取上下文', '检索资料', '生成讲解并校验出处'] as const;

const route = useRoute();
const router = useRouter();

const run = ref<RunState | null>(null);
/** 404/403:运行不可见(不存在/非属主),轮询终止 */
const loadError = ref('');
/** 重试遇 429:配额 envelope 文案以页内错误态展示(04 §8.6) */
const quotaMsg = ref('');
const retrying = ref(false);
/** 提交上下文(含 question):路由 state 带入,重试复用同一 payload */
const launch = ref<RunSubmitPayload | null>(null);

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

// —— 轮询:挂载即首轮 + 2s 间隔;终态/不可见即停,404/403 终止,网络抖动静默续拍 ——

async function poll(): Promise<void> {
  try {
    const state = await fetchRun(Number(route.params.id));
    run.value = state;
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
  launch.value = readLaunch();
  startPolling();
  document.addEventListener('visibilitychange', onVisibility);
});

onBeforeUnmount(() => {
  stopPolling();
  document.removeEventListener('visibilitychange', onVisibility);
});

// —— 失败重试:同一 payload 重新提交 → 429 页内错误态,其余 toast;成功换新 run 续拍 ——

async function retry(): Promise<void> {
  if (retrying.value || !launch.value) return;
  retrying.value = true;
  try {
    const { runId: nextId } = await submitRun(launch.value);
    await router.replace({ path: `/runs/${nextId}`, state: { keRun: JSON.stringify(launch.value) } });
    run.value = null;
    loadError.value = '';
    startPolling();
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
  <div class="page">
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

    <!-- DONE:简单成功卡(Task 22 结果页接入后整块替换;「查看讲解」先行占位) -->
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
          「{{ question }}」已完成,查看讲解即将上线
        </span>
        <button
          type="button"
          class="done-view"
          disabled
          title="结果页即将上线"
        >
          查看讲解
        </button>
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
  </div>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; padding: 52px 16px 40px; background: var(--ke-bg); }
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

/* 成功/错误卡(KCard 族:白底细线描边圆角 l) */
.done-card, .err-card { margin-top: 8px; padding: 26px 18px 22px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.done-ic, .err-ic { display: inline-flex; width: 40px; height: 40px; align-items: center; justify-content: center; border-radius: var(--ke-radius-full); }
.done-ic { background: var(--ke-fact-soft); color: var(--ke-fact); }
.err-ic { background: var(--ke-surface-2); color: var(--ke-sub-2); }
.done-ic .ke-icon, .err-ic .ke-icon { width: 22px; height: 22px; }
.done-t, .err-t { display: block; margin-top: 12px; font-size: 14px; font-weight: 600; line-height: 1.6; color: var(--ke-ink); }
.done-s, .err-reason { display: block; margin-top: 6px; font-size: 12px; line-height: 1.7; color: var(--ke-sub); overflow-wrap: anywhere; }
.done-view, .err-retry, .err-alt { display: block; width: 100%; margin-top: 12px; padding: 10px 0; border-radius: var(--ke-radius-m); font-size: 13px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; box-sizing: border-box; }
.done-view { border: none; background: var(--ke-primary-soft); color: var(--ke-primary); }
.err-retry { border: none; background: var(--ke-primary-soft); color: var(--ke-primary); }
.done-view:disabled, .err-retry:disabled { opacity: 0.45; cursor: default; }
.err-alt { border: 1px solid var(--ke-line-strong); background: var(--ke-surface); color: var(--ke-ink); }
</style>
