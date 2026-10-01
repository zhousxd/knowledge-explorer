import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { createPinia } from 'pinia';
import { ApiError } from '../api/http';
import { fetchRun, isTerminal, submitRun } from '../api/runs';
import type { RunArtifact, RunState, RunSubmitPayload } from '../api/runs';
import { updateExplainLevel } from '../api/sessions';
import RunView from '../views/RunView.vue';

vi.mock('../api/runs', () => ({ fetchRun: vi.fn(), submitRun: vi.fn(), isTerminal: vi.fn() }));
vi.mock('../api/sessions', () => ({ createSession: vi.fn(), updateExplainLevel: vi.fn() }));
vi.mock('vant', () => ({ showToast: vi.fn() }));
const mockedFetchRun = vi.mocked(fetchRun);
const mockedSubmit = vi.mocked(submitRun);
const mockedIsTerminal = vi.mocked(isTerminal);
const mockedUpdateLevel = vi.mocked(updateExplainLevel);
const mockedToast = vi.mocked((await import('vant')).showToast);

/** isTerminal 用真实现(mock 工厂清掉了原模块,统一在 beforeEach 钉冻结语义) */
const TERMINAL = new Set(['DONE', 'FAILED', 'TIMEOUT']);

const PAYLOAD: RunSubmitPayload = {
  cardVersionId: 11, sessionId: 3, nodeId: 6, question: '讲清楚:岳麓书院', level: 'SIMPLE'
};

/** DONE 全键 artifact(= ExplainResult content_json:output 嵌套 + sources/disclaimer/audit) */
const ARTIFACT: RunArtifact = {
  output: {
    summary: '岳麓书院创办于北宋开宝九年。',
    sections: [
      { body: '书院由潭州太守朱洞创建。', claimType: 'FACT', citations: [11] },
      { body: '朱张会讲开书院会讲之先河。', claimType: 'SYNTHESIS', citations: [] },
      { body: '可以说书院精神延续至今。', claimType: 'GEN' }
    ],
    openQuestions: ['书院经费从何而来?', '讲学制度如何运作?'],
    evidenceGaps: ['缺少宋代讲会实录']
  },
  sources: { '11': '《岳麓书院史略》第一章,书院创建于北宋开宝九年,朱熹曾在此讲学' },
  disclaimer: '本内容由 AI 生成,仅供参考',
  audit: { stripped: 2, filtered: 1 }
};

function runState(patch: Partial<RunState>): RunState {
  return {
    runId: 7, status: 'RUNNING', model: null, latencyMs: null, error: null, artifact: null, ...patch
  };
}

/** 直挂 RunView 的独立 memory 路由;state 经路由 history state 传入(与生产 router.push 一致)。
 *  pinia 种入会话(模拟「从卡片页点服务键而来」:ensureForCard 已建 session 3,档位 SIMPLE)。 */
async function mountRun(id = '7', opts: { state?: Record<string, unknown> } = {}) {
  const pinia = createPinia();
  pinia.state.value.session = { sessionId: 3, theme: 'academy', goal: '', explainLevel: 'SIMPLE' };
  const local = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/runs/:id(\\d+)', component: RunView },
      { path: '/cards/1', component: { render: () => null } },
      { path: '/home', component: { render: () => null } }
    ]
  });
  await local.push({ path: `/runs/${id}`, state: opts.state as never });
  await local.isReady();
  const wrapper = mount(RunView, { global: { plugins: [local, pinia] } });
  await flushPromises();
  // 逐测卸载:轮询定时器与 document 级 visibilitychange 监听器不得跨测试泄漏
  onTestFinished(() => {
    try {
      wrapper.unmount();
    } catch {
      // 用例内已手动卸载(卸载停止轮询用例)
    }
  });
  return { wrapper, local };
}

function setHidden(hidden: boolean): void {
  Object.defineProperty(document, 'visibilityState', { value: hidden ? 'hidden' : 'visible', configurable: true });
  document.dispatchEvent(new Event('visibilitychange'));
}

describe('RunView(执行态页,04 §7.2 RunProgress)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // jsdom 无 scrollIntoView(出处联动高亮滚动,P3-15 同模式)
    Element.prototype.scrollIntoView = vi.fn();
    mockedFetchRun.mockResolvedValue(runState({}));
    mockedSubmit.mockResolvedValue({ runId: 42 });
    mockedIsTerminal.mockImplementation((s) => TERMINAL.has(s));
    mockedUpdateLevel.mockResolvedValue({ explainLevel: 'DEEP' });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('运行中:spinner + 任务问题(路由 state 带入)+ 三步清单(前两 ✓ 第三 ◐)+ 限时/可离开说明', async () => {
    const { wrapper } = await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });
    expect(wrapper.find('.run-spin').exists()).toBe(true);
    expect(wrapper.find('.run-q').text()).toBe('讲清楚:岳麓书院');
    const steps = wrapper.findAll('.step');
    expect(steps).toHaveLength(3);
    expect(steps[0]?.classes()).toContain('done');
    expect(steps[1]?.classes()).toContain('done');
    expect(steps[2]?.classes()).toContain('doing');
    expect(steps[2]?.text()).toContain('生成讲解并校验出处');
    expect(wrapper.text()).toContain('单次任务限时 60 秒');
    expect(wrapper.text()).toContain('可以离开此页');
  });

  it('刷新丢 state:任务问题回落「智能服务执行中」,不白屏', async () => {
    const { wrapper } = await mountRun('7');
    expect(wrapper.find('.run-q').text()).toBe('智能服务执行中');
  });

  it('轮询:2s 间隔 QUEUED→DONE,终态渲染讲解结果页并停止轮询', async () => {
    vi.useFakeTimers();
    mockedFetchRun
      .mockResolvedValueOnce(runState({ status: 'QUEUED' }))
      .mockResolvedValueOnce(runState({ status: 'DONE', artifact: ARTIFACT }));
    const { wrapper } = await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });
    expect(mockedFetchRun).toHaveBeenCalledTimes(1); // 挂载即首轮
    expect(wrapper.find('.result-wrap').exists()).toBe(false);

    await vi.advanceTimersByTimeAsync(2000);
    await flushPromises();
    expect(mockedFetchRun).toHaveBeenCalledTimes(2);
    // 结果页:讲解卡 chip + 档位 chip + 宋体问题标题
    expect(wrapper.find('.result-wrap').exists()).toBe(true);
    expect(wrapper.find('.chip.gen-tag').text()).toBe('讲解卡 · 由智能体生成');
    expect(wrapper.find('.chip.lvl').text()).toContain('简明');
    expect(wrapper.find('.q-title').text()).toBe('讲清楚:岳麓书院');
    expect(wrapper.find('.run-spin').exists()).toBe(false);
    // 终态后轮询停止:再推进也不调
    await vi.advanceTimersByTimeAsync(6000);
    expect(mockedFetchRun).toHaveBeenCalledTimes(2);
  });

  it('结果页正文:summary 引言段 + 三段正文各带 ClaimBadge 三档 + disclaimer 脚注', async () => {
    mockedFetchRun.mockResolvedValue(runState({ status: 'DONE', artifact: ARTIFACT }));
    const { wrapper } = await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });
    expect(wrapper.find('.sum').text()).toBe('岳麓书院创办于北宋开宝九年。');
    const paras = wrapper.findAll('.para');
    expect(paras).toHaveLength(3);
    expect(paras[0]?.text()).toContain('书院由潭州太守朱洞创建。');
    expect(paras[0]?.find('.claim').classes()).toContain('c-fact');
    expect(paras[1]?.find('.claim').classes()).toContain('c-synth');
    expect(paras[2]?.find('.claim').classes()).toContain('c-gen');
    // FACT 段段尾角标 [1](assetId 11 → sources 首行),title 为出处文本缩略
    const cite = paras[0]?.find('.cite');
    expect(cite?.text()).toBe('[1]');
    expect(cite?.attributes('title')).toContain('《岳麓书院史略》');
    expect(wrapper.find('.foot').text()).toBe('本内容由 AI 生成,仅供参考');
  });

  it('出处清单:sources map 逐行 [n] 文本截断;角标点击高亮对应行,2s 回落', async () => {
    vi.useFakeTimers();
    mockedFetchRun.mockResolvedValue(runState({ status: 'DONE', artifact: ARTIFACT }));
    const { wrapper } = await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });
    const rows = wrapper.findAll('.src .row');
    expect(rows).toHaveLength(1);
    expect(rows[0]?.text()).toContain('[1]');
    expect(rows[0]?.text()).toContain('《岳麓书院史略》第一章');
    expect(rows[0]?.text().length).toBeLessThanOrEqual('[1] '.length + 30 + 1); // 截断 ≤30 字

    await wrapper.find('.para .cite').trigger('click');
    expect(rows[0]?.classes()).toContain('row-hl');
    await vi.advanceTimersByTimeAsync(2100);
    expect(wrapper.find('.src .row.row-hl').exists()).toBe(false);
  });

  it('引用审计与证据缺口:stripped>0 警示行 + evidenceGaps 逐条 ⚠ 警示行(alert 图标)', async () => {
    mockedFetchRun.mockResolvedValue(runState({ status: 'DONE', artifact: ARTIFACT }));
    const { wrapper } = await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });
    const warns = wrapper.findAll('.src .warn');
    expect(warns).toHaveLength(2);
    expect(warns[0]?.text()).toContain('引用校验:已剥离 2 个无效引用');
    expect(warns[1]?.text()).toContain('证据缺口');
    expect(warns[1]?.text()).toContain('缺少宋代讲会实录');
    expect(warns[1]?.find('.ke-icon').exists()).toBe(true); // i-alert 图标,非 emoji 字符
  });

  it('还可以继续问:openQuestions 渲染可点行,点击预填追问输入框', async () => {
    mockedFetchRun.mockResolvedValue(runState({ status: 'DONE', artifact: ARTIFACT }));
    const { wrapper } = await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });
    const qs = wrapper.findAll('.more-q');
    expect(qs).toHaveLength(2);
    expect(wrapper.find('.ask-in').exists()).toBe(true);
    expect((wrapper.find('.ask-in').element as HTMLInputElement).value).toBe('');

    await qs[0]!.trigger('click');
    expect((wrapper.find('.ask-in').element as HTMLInputElement).value).toBe('书院经费从何而来?');
  });

  it('追问发送:submitRun 继承提交上下文 + parentRunId=当前 run,跳新 run 轮询', async () => {
    mockedFetchRun.mockResolvedValue(runState({ status: 'DONE', artifact: ARTIFACT }));
    const { wrapper, local } = await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });

    await wrapper.find('.ask-in').setValue('那书院经费呢?');
    await wrapper.find('.ask-send').trigger('click');
    await flushPromises();

    expect(mockedSubmit).toHaveBeenCalledTimes(1);
    expect(mockedSubmit).toHaveBeenCalledWith({
      cardVersionId: 11, sessionId: 3, nodeId: 6,
      question: '那书院经费呢?', level: 'SIMPLE', parentRunId: 7
    });
    expect(local.currentRoute.value.path).toBe('/runs/42');
    expect(mockedFetchRun).toHaveBeenLastCalledWith(42);
  });

  it('刷新丢 state:追问禁用(placeholder 说明原因),发送不触发提交', async () => {
    mockedFetchRun.mockResolvedValue(runState({ status: 'DONE', artifact: ARTIFACT }));
    const { wrapper } = await mountRun('7');
    expect(wrapper.find('.ask-in').attributes('disabled')).toBeDefined();
    expect(wrapper.find('.ask-in').attributes('placeholder')).toContain('刷新后无法追问');
    expect(wrapper.find('.ask-send').attributes('disabled')).toBeDefined();

    await wrapper.find('.ask-in').setValue('还能问吗');
    await wrapper.find('.ask-send').trigger('click');
    await flushPromises();
    expect(mockedSubmit).not.toHaveBeenCalled();
  });

  it('档位 chip 点击:循环切换调 PUT /sessions/{id}/explain-level + toast 下次生效', async () => {
    mockedFetchRun.mockResolvedValue(runState({ status: 'DONE', artifact: ARTIFACT }));
    const { wrapper } = await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });

    await wrapper.find('.chip.lvl').trigger('click');
    await flushPromises();
    expect(mockedUpdateLevel).toHaveBeenCalledWith(3, 'DEEP');
    expect(wrapper.find('.chip.lvl').text()).toContain('深入');
    expect(mockedToast).toHaveBeenCalledWith('已切换,下次讲解生效');
  });

  it('FAILED:错误卡按 04 §8.4 模板,重试以原 payload 重新提交并跳转新 run', async () => {
    mockedFetchRun.mockResolvedValue(runState({ status: 'FAILED', error: '上游服务异常' }));
    const { wrapper, local } = await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });
    const err = wrapper.find('.err-card');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain('生成没有成功,你的路径不受影响');
    expect(err.text()).toContain('上游服务异常');
    expect(wrapper.find('.run-spin').exists()).toBe(false);

    await wrapper.find('.err-retry').trigger('click');
    await flushPromises();
    expect(mockedSubmit).toHaveBeenCalledTimes(1);
    expect(mockedSubmit).toHaveBeenCalledWith(PAYLOAD);
    expect(local.currentRoute.value.path).toBe('/runs/42');
    // 新 run 重新进入轮询
    expect(mockedFetchRun).toHaveBeenLastCalledWith(42);
  });

  it('TIMEOUT 且 error 缺键:错误卡原因回落「任务超时」', async () => {
    mockedFetchRun.mockResolvedValue(runState({ status: 'TIMEOUT' }));
    const { wrapper } = await mountRun('7');
    const err = wrapper.find('.err-card');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain('任务超时');
  });

  it('重试遇 429:页内错误态显示 envelope 文案,不再停留 spinner', async () => {
    mockedFetchRun.mockResolvedValue(runState({ status: 'FAILED', error: 'x' }));
    mockedSubmit.mockRejectedValue(new ApiError(429, '今日 30 次智能服务已用完,明早 8 点恢复'));
    const { wrapper } = await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });
    await wrapper.find('.err-retry').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('今日 30 次智能服务已用完,明早 8 点恢复');
    expect(wrapper.find('.run-spin').exists()).toBe(false);
  });

  it('页面隐藏暂停轮询,回前台即刻补一拍并恢复间隔', async () => {
    vi.useFakeTimers();
    await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });
    expect(mockedFetchRun).toHaveBeenCalledTimes(1);
    setHidden(true);
    await vi.advanceTimersByTimeAsync(6000);
    expect(mockedFetchRun).toHaveBeenCalledTimes(1); // 隐藏期间不轮询
    setHidden(false);
    await flushPromises();
    expect(mockedFetchRun).toHaveBeenCalledTimes(2); // 回前台即刻补一拍
    await vi.advanceTimersByTimeAsync(2000);
    await flushPromises();
    expect(mockedFetchRun).toHaveBeenCalledTimes(3); // 间隔轮询恢复
  });

  it('组件卸载后停止轮询(定时器清理)', async () => {
    vi.useFakeTimers();
    const { wrapper } = await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });
    expect(mockedFetchRun).toHaveBeenCalledTimes(1);
    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(6000);
    expect(mockedFetchRun).toHaveBeenCalledTimes(1);
  });

  it('404/403:置「运行不存在或无权访问」错误态并停止轮询', async () => {
    vi.useFakeTimers();
    mockedFetchRun.mockRejectedValue(new ApiError(403, '无权访问该运行'));
    const { wrapper } = await mountRun('7');
    expect(wrapper.text()).toContain('运行不存在或无权访问');
    expect(wrapper.find('.run-spin').exists()).toBe(false);
    await vi.advanceTimersByTimeAsync(6000);
    expect(mockedFetchRun).toHaveBeenCalledTimes(1);
  });
});
