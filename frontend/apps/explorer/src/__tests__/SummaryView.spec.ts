import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { createPinia } from 'pinia';
import { showToast } from 'vant';
import { ApiError } from '../api/http';
import { fetchRun, isTerminal, summarize } from '../api/runs';
import type { RunState, RunSubmitContext, SummarizePayload } from '../api/runs';
import { fetchOpenQuestions, fetchSessionTree } from '../api/sessions';
import type { OpenQuestionItem, PathNode, SessionTree } from '../api/sessions';
import SummaryView from '../views/SummaryView.vue';

vi.mock('../api/runs', () => ({ fetchRun: vi.fn(), summarize: vi.fn(), isTerminal: vi.fn() }));
vi.mock('../api/sessions', () => ({ fetchSessionTree: vi.fn(), fetchOpenQuestions: vi.fn() }));
vi.mock('vant', () => ({ showToast: vi.fn() }));
const mockedTree = vi.mocked(fetchSessionTree);
const mockedOqs = vi.mocked(fetchOpenQuestions);
const mockedSummarize = vi.mocked(summarize);
const mockedFetchRun = vi.mocked(fetchRun);
const mockedIsTerminal = vi.mocked(isTerminal);

/** isTerminal 用真实现(mock 工厂清掉了原模块,统一在 beforeEach 钉冻结语义) */
const TERMINAL = new Set(['DONE', 'FAILED', 'TIMEOUT']);

let seq = 0;
function node(partial: Partial<PathNode>): PathNode {
  seq += 1;
  return {
    nodeId: partial.nodeId ?? seq,
    parentNodeId: partial.parentNodeId ?? null,
    cardVersionId: partial.cardVersionId ?? null,
    entryId: partial.entryId ?? null,
    questionText: partial.questionText ?? null,
    isNewKnowledge: partial.isNewKnowledge ?? false,
    visitedAt: partial.visitedAt ?? '2026-09-30T10:00:00Z',
    cardTitle: partial.cardTitle ?? null
  };
}

/** 两支会话树:A 支 1→2→3(3 为孙节点),B 支 4→5 —— 按根分组渲染(FR-E07 跨分支勾选) */
const TREE: SessionTree = {
  sessionId: 5,
  theme: 'academy',
  goal: '读懂岳麓书院',
  explainLevel: 'SIMPLE',
  status: 'ACTIVE',
  createdAt: '2026-09-28T09:00:00Z',
  updatedAt: '2026-09-30T10:00:00Z',
  nodes: [
    node({ nodeId: 1, cardTitle: '岳麓书院：从选址到人物', isNewKnowledge: true }),
    node({ nodeId: 2, parentNodeId: 1, cardTitle: '为什么建在这里', isNewKnowledge: true }),
    node({ nodeId: 3, parentNodeId: 2, questionText: '朱张会讲是谁主持的？' }),
    node({ nodeId: 4, cardTitle: '书院与山寺的关系', isNewKnowledge: true }),
    node({ nodeId: 5, parentNodeId: 4, cardTitle: '藏书的去向', isNewKnowledge: true })
  ]
};

const OQS: { questions: OpenQuestionItem[] } = {
  questions: [
    { runId: 42, question: '书院经费从何而来?', collectedAt: '2026-09-30T09:30:00Z' },
    { runId: 41, question: '朱张会讲是谁主持的?', collectedAt: '2026-09-29T10:00:00Z' }
  ]
};

/** DONE 全键 REPORT artifact(Task 24 content_json 扁平键:branchView 2 支) */
const REPORT_RUN: RunState = {
  runId: 7,
  status: 'DONE',
  serviceType: 'SUMMARIZE',
  model: 'deepseek-chat',
  latencyMs: 5200,
  error: null,
  submitContext: null,
  artifact: {
    type: 'REPORT',
    keyFindings: [
      { body: '书院由潭州太守朱洞创建于北宋开宝九年。', claimType: 'FACT', citations: [11] },
      { body: '朱张会讲开书院会讲之先河,可以说书院精神延续至今。', claimType: 'SYNTHESIS', citations: [] }
    ],
    openQuestions: ['书院经费从何而来?'],
    branchView: [
      { rootNodeTitle: '岳麓书院：从选址到人物', nodeTitles: ['为什么建在这里', '朱张会讲是谁主持的？'] },
      { rootNodeTitle: '书院与山寺的关系', nodeTitles: ['藏书的去向'] }
    ],
    sources: { '11': '《岳麓书院史略》第一章,书院创建于北宋开宝九年,朱熹曾在此讲学' },
    disclaimer: '本内容由 AI 生成,仅供参考',
    audit: { stripped: 2, filtered: 0 }
  }
};

function runState(patch: Partial<RunState>): RunState {
  return {
    runId: 7, status: 'RUNNING', serviceType: 'SUMMARIZE', model: null, latencyMs: null,
    error: null, artifact: null, submitContext: null, ...patch
  };
}

/** 来源讲解 run 的 submitContext 投影(review P5-FIX):「去追问」组装 keRun 的依据 */
const OQ_SOURCE_CTX: RunSubmitContext = {
  cardVersionId: 11, sessionId: 5, nodeId: 2,
  serviceType: 'EXPLAIN', question: '书院经费从何而来?'
};

/** 直挂 SummaryView 的独立 memory 路由(含 /path /runs 兜底路由供跳转断言);
 *  state 经路由 history state 传入(与生产 router.push 一致,如 keSummary 提交上下文)。 */
async function mountSummary(
  query = '?sessionId=5',
  opts: { state?: Record<string, unknown> } = {}
) {
  const pinia = createPinia();
  const local = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/summary', component: SummaryView },
      { path: '/path', component: { render: () => null } },
      { path: '/runs/:id(\\d+)', component: { render: () => null } },
      { path: '/home', component: { render: () => null } }
    ]
  });
  await local.push({
    path: '/summary',
    // query 串 → 对象(vue-router 对象式 path 不解析查询串);state 与生产 push 同形
    query: query ? Object.fromEntries(new URLSearchParams(query)) : undefined,
    state: opts.state as never
  });
  await local.isReady();
  const wrapper = mount(SummaryView, { global: { plugins: [local, pinia] } });
  await flushPromises();
  onTestFinished(() => {
    try {
      wrapper.unmount();
    } catch {
      // 用例内已手动卸载(卸载停止轮询用例)
    }
  });
  return { wrapper, local };
}

describe('SummaryView(成果整理页,FR-E07 勾选阶段)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Element.prototype.scrollIntoView = vi.fn();
    mockedIsTerminal.mockImplementation((s) => TERMINAL.has(s));
    mockedTree.mockResolvedValue(TREE);
    mockedOqs.mockResolvedValue(OQS);
    mockedSummarize.mockResolvedValue({ runId: 88 });
    mockedFetchRun.mockResolvedValue(runState({}));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('按分支分组渲染:每根一支(根行=分支标题,子孙缩进),共 2 支 5 行', async () => {
    const { wrapper } = await mountSummary('?sessionId=5');
    expect(mockedTree).toHaveBeenCalledWith(5);

    const branches = wrapper.findAll('.branch');
    expect(branches).toHaveLength(2);
    // A 支:根行 + 2 个缩进子孙(含孙节点);B 支:根行 + 1 子
    expect(branches[0]!.find('.b-t').text()).toBe('岳麓书院：从选址到人物');
    expect(branches[0]!.findAll('.n-row')).toHaveLength(2);
    expect(branches[1]!.find('.b-t').text()).toBe('书院与山寺的关系');
    expect(branches[1]!.findAll('.n-row')).toHaveLength(1);
    // 纯追问节点回落 questionText
    expect(branches[0]!.findAll('.n-row')[1]!.text()).toContain('朱张会讲是谁主持的？');
  });

  it('默认全不选;「全选」一键勾满 5 个;单行取消联动计数;全选后按钮禁用', async () => {
    const { wrapper } = await mountSummary('?sessionId=5');

    expect(wrapper.find('.sel-count').text()).toContain('已选 0 个节点');
    const go = wrapper.find('.sum-go');
    expect(go.attributes('disabled')).toBeDefined();

    await wrapper.find('.sel-all').trigger('click');
    expect(wrapper.find('.sel-count').text()).toContain('已选 5 个节点');
    expect(wrapper.findAll('.ck').every((c) => (c.element as HTMLInputElement).checked)).toBe(true);
    // 全部已选:全选按钮禁用(决策:默认全不选 + 顶部「全选」)
    expect(wrapper.find('.sel-all').attributes('disabled')).toBeDefined();
    expect(go.attributes('disabled')).toBeUndefined();

    await wrapper.findAll('.n-row .ck')[0]!.setValue(false);
    expect(wrapper.find('.sel-count').text()).toContain('已选 4 个节点');
    expect(wrapper.find('.sel-all').attributes('disabled')).toBeUndefined();
  });

  it('跨分支勾选 2 个节点提交:summarize 带 {sessionId,nodeIds},跳 runId 报告态', async () => {
    const { wrapper, local } = await mountSummary('?sessionId=5');

    // A 支根 + B 支根:跨分支勾选(FR-E07)
    const branchA = wrapper.findAll('.branch')[0]!;
    const branchB = wrapper.findAll('.branch')[1]!;
    await branchA.find('.b-row .ck').setValue(true);
    await branchB.find('.b-row .ck').setValue(true);
    await wrapper.find('.sum-go').trigger('click');
    await flushPromises();

    expect(mockedSummarize).toHaveBeenCalledTimes(1);
    const payload = mockedSummarize.mock.calls[0]?.[0] as SummarizePayload;
    expect(payload.sessionId).toBe(5);
    expect([...payload.nodeIds].sort()).toEqual([1, 4]);
    expect(local.currentRoute.value.path).toBe('/summary');
    expect(local.currentRoute.value.query.runId).toBe('88');
  });

  it('空选提交被禁用并短路不调 summarize;busy 防双击', async () => {
    const { wrapper } = await mountSummary('?sessionId=5');
    expect(wrapper.find('.sum-go').attributes('disabled')).toBeDefined();
    await wrapper.find('.sum-go').trigger('click');
    expect(mockedSummarize).not.toHaveBeenCalled();

    // busy 防双击:submitting 期间再点不重复提交
    await wrapper.find('.sel-all').trigger('click');
    let release: (v: { runId: number }) => void = () => {};
    mockedSummarize.mockReturnValueOnce(new Promise((res) => { release = res; }));
    await wrapper.find('.sum-go').trigger('click');
    await wrapper.find('.sum-go').trigger('click');
    release({ runId: 88 });
    await flushPromises();
    expect(mockedSummarize).toHaveBeenCalledTimes(1);
  });

  it('提交遇 429:配额 envelope 文案页内展示(.quota-err),不跳报告态', async () => {
    mockedSummarize.mockRejectedValue(new ApiError(429, '今日 30 次智能服务已用完,明早 8 点恢复'));
    const { wrapper, local } = await mountSummary('?sessionId=5');

    await wrapper.find('.sel-all').trigger('click');
    await wrapper.find('.sum-go').trigger('click');
    await flushPromises();

    expect(wrapper.find('.quota-err').text()).toContain('今日 30 次智能服务已用完');
    expect(local.currentRoute.value.query.runId).toBeUndefined();
    expect(mockedFetchRun).not.toHaveBeenCalled();
  });

  it('query 缺 sessionId:重定向回 /path,不拉树不拉疑问', async () => {
    const { wrapper, local } = await mountSummary('');
    await flushPromises();
    expect(local.currentRoute.value.path).toBe('/path');
    expect(mockedTree).not.toHaveBeenCalled();
    expect(wrapper.find('.branch').exists()).toBe(false);
  });

  it('树加载失败(403 非属主):错误空态,不渲染勾选区', async () => {
    mockedTree.mockRejectedValue(new ApiError(403, '无权访问该会话'));
    const { wrapper } = await mountSummary('?sessionId=5');
    expect(wrapper.find('.load-err').exists()).toBe(true);
    expect(wrapper.find('.load-err').text()).toContain('无权访问该会话');
    expect(wrapper.find('.sum-go').exists()).toBe(false);
  });

  it('页面底部未决问题区(勾选阶段即有):每条含来源时间,「去追问」跳 /runs/{runId}', async () => {
    const { wrapper, local } = await mountSummary('?sessionId=5');
    expect(mockedOqs).toHaveBeenCalledWith(5);
    const rows = wrapper.findAll('.oq-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]!.text()).toContain('书院经费从何而来?');
    expect(rows[0]!.text()).toContain('9月30日');
    expect(rows[1]!.text()).toContain('9月29日');

    // 去追问(review P5-FIX):fetchRun 取 submitContext 组装 keRun state → 落地即可追问
    mockedFetchRun.mockResolvedValue({
      runId: 42, status: 'DONE', serviceType: 'EXPLAIN', model: 'deepseek-chat', latencyMs: 4200,
      error: null, artifact: null, submitContext: OQ_SOURCE_CTX
    });
    await rows[0]!.find('.oq-go').trigger('click');
    await flushPromises();
    expect(mockedFetchRun).toHaveBeenCalledWith(42);
    expect(local.currentRoute.value.path).toBe('/runs/42');
    expect(JSON.parse((local.options.history.state as Record<string, unknown>).keRun as string)).toEqual({
      cardVersionId: 11, sessionId: 5, nodeId: 2,
      question: '书院经费从何而来?', level: 'SIMPLE'
    });
  });

  it('去追问失败(fetchRun 404/网络):toast「暂时无法追问」,停留整理页', async () => {
    mockedFetchRun.mockRejectedValue(new ApiError(404, '运行不存在'));
    const { wrapper, local } = await mountSummary('?sessionId=5');
    await wrapper.findAll('.oq-row')[0]!.find('.oq-go').trigger('click');
    await flushPromises();
    expect(showToast).toHaveBeenCalledWith('暂时无法追问');
    expect(local.currentRoute.value.path).toBe('/summary');
  });

  it('去追问但 submitContext 不完整(缺键):toast「暂时无法追问」,不跳转', async () => {
    mockedFetchRun.mockResolvedValue(runState({ runId: 42 }));
    const { wrapper, local } = await mountSummary('?sessionId=5');
    await wrapper.findAll('.oq-row')[0]!.find('.oq-go').trigger('click');
    await flushPromises();
    expect(showToast).toHaveBeenCalledWith('暂时无法追问');
    expect(local.currentRoute.value.path).toBe('/summary');
  });
});

describe('SummaryView(成果整理页,FR-E07 报告态:query.runId 轮询)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Element.prototype.scrollIntoView = vi.fn();
    mockedIsTerminal.mockImplementation((s) => TERMINAL.has(s));
    mockedTree.mockResolvedValue(TREE);
    mockedOqs.mockResolvedValue(OQS);
    mockedSummarize.mockResolvedValue({ runId: 99 });
    mockedFetchRun.mockResolvedValue(runState({}));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('QUEUED/RUNNING:spinner +「整理中」,2s 轮询至 DONE 渲染报告并停止', async () => {
    vi.useFakeTimers();
    mockedFetchRun
      .mockResolvedValueOnce(runState({ status: 'QUEUED' }))
      .mockResolvedValueOnce(REPORT_RUN);
    const { wrapper } = await mountSummary('?sessionId=5&runId=7', {
      state: { keSummary: JSON.stringify({ sessionId: 5, nodeIds: [1, 4] }) }
    });
    expect(mockedFetchRun).toHaveBeenCalledTimes(1); // 挂载即首轮
    expect(wrapper.find('.run-spin').exists()).toBe(true);
    expect(wrapper.text()).toContain('整理中');
    expect(wrapper.find('.report-wrap').exists()).toBe(false);
    // 勾选区与底部提交条不与运行态同现
    expect(wrapper.find('.sum-go').exists()).toBe(false);

    await vi.advanceTimersByTimeAsync(2000);
    await flushPromises();
    expect(mockedFetchRun).toHaveBeenCalledTimes(2);
    expect(wrapper.find('.report-wrap').exists()).toBe(true);
    expect(wrapper.find('.run-spin').exists()).toBe(false);
    // 终态停轮询
    await vi.advanceTimersByTimeAsync(6000);
    expect(mockedFetchRun).toHaveBeenCalledTimes(2);
  });

  it('DONE 报告渲染:标题宋体「探索报告」+ 分支视图 2 支 + 关键发现徽标/角标 + 出处清单 + 脚注', async () => {
    mockedFetchRun.mockResolvedValue(REPORT_RUN);
    const { wrapper } = await mountSummary('?sessionId=5&runId=7');

    expect(wrapper.find('.report-wrap').exists()).toBe(true);
    expect(wrapper.find('.r-title').text()).toBe('探索报告');
    // 分支视图:branchView 2 支,每支根标题 + 子节点题列表
    const rows = wrapper.findAll('.bv-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]!.find('.bv-root').text()).toBe('岳麓书院：从选址到人物');
    expect(rows[0]!.findAll('.bv-node')).toHaveLength(2);
    expect(rows[0]!.findAll('.bv-node')[0]!.text()).toBe('为什么建在这里');
    expect(rows[1]!.findAll('.bv-node')).toHaveLength(1);
    // 关键发现:ClaimBadge 三档(FACT/SYNTHESIS)+ citations 角标 [1](title=出处缩略)
    const findings = wrapper.findAll('.finding');
    expect(findings).toHaveLength(2);
    expect(findings[0]!.text()).toContain('书院由潭州太守朱洞创建');
    expect(findings[0]!.find('.claim').classes()).toContain('c-fact');
    expect(findings[1]!.find('.claim').classes()).toContain('c-synth');
    const cite = findings[0]!.find('.cite');
    expect(cite.text()).toBe('[1]');
    expect(cite.attributes('title')).toContain('《岳麓书院史略》');
    // 出处清单 + 剥离警示 + disclaimer 脚注
    expect(wrapper.findAll('.src .row')).toHaveLength(1);
    expect(wrapper.find('.src .warn').text()).toContain('已剥离 2 个无效引用');
    expect(wrapper.find('.foot').text()).toBe('本内容由 AI 生成,仅供参考');
    // 报告态的未决问题区:两条来源疑问 + 底部会话疑问卡都渲染
    expect(wrapper.findAll('.r-q')).toHaveLength(1);
    expect(wrapper.findAll('.oq-row')).toHaveLength(2);
  });

  it('角标点击:出处清单对应行高亮(row-hl)', async () => {
    mockedFetchRun.mockResolvedValue(REPORT_RUN);
    const { wrapper } = await mountSummary('?sessionId=5&runId=7');

    await wrapper.find('.finding .cite').trigger('click');
    expect(wrapper.find('.src .row.row-hl').exists()).toBe(true);
  });

  it('FAILED:错误卡 + 重试以原 nodeIds 重新 summarize,跳新 runId 续拍', async () => {
    mockedFetchRun.mockResolvedValue(runState({ status: 'FAILED', error: '上游服务异常' }));
    const { wrapper, local } = await mountSummary('?sessionId=5&runId=7', {
      state: { keSummary: JSON.stringify({ sessionId: 5, nodeIds: [1, 4] }) }
    });

    const err = wrapper.find('.err-card');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain('整理没有成功');
    expect(err.text()).toContain('上游服务异常');

    await wrapper.find('.err-retry').trigger('click');
    await flushPromises();
    expect(mockedSummarize).toHaveBeenCalledWith({ sessionId: 5, nodeIds: [1, 4] });
    expect(local.currentRoute.value.query.runId).toBe('99');
    expect(mockedFetchRun).toHaveBeenLastCalledWith(99);
  });

  it('刷新丢提交上下文(无 keSummary state):重试禁用并带原因', async () => {
    mockedFetchRun.mockResolvedValue(runState({ status: 'FAILED', error: 'x' }));
    const { wrapper } = await mountSummary('?sessionId=5&runId=7');

    const retry = wrapper.find('.err-retry');
    expect(retry.attributes('disabled')).toBeDefined();
    expect(retry.attributes('title')).toContain('请返回重新发起');
    await retry.trigger('click');
    expect(mockedSummarize).not.toHaveBeenCalled();
  });

  it('重试遇 429:配额 envelope 文案页内错误卡展示,不停留 spinner', async () => {
    mockedFetchRun.mockResolvedValue(runState({ status: 'FAILED', error: 'x' }));
    mockedSummarize.mockRejectedValue(new ApiError(429, '今日 30 次智能服务已用完,明早 8 点恢复'));
    const { wrapper } = await mountSummary('?sessionId=5&runId=7', {
      state: { keSummary: JSON.stringify({ sessionId: 5, nodeIds: [1] }) }
    });

    await wrapper.find('.err-retry').trigger('click');
    await flushPromises();
    expect(wrapper.find('.err-card').text()).toContain('今日 30 次智能服务已用完');
    expect(wrapper.find('.run-spin').exists()).toBe(false);
  });

  it('轮询 404/403:置「运行不存在或无权访问」并停止轮询', async () => {
    vi.useFakeTimers();
    mockedFetchRun.mockRejectedValue(new ApiError(403, '无权访问该运行'));
    const { wrapper } = await mountSummary('?sessionId=5&runId=7');
    expect(wrapper.text()).toContain('运行不存在或无权访问');
    expect(wrapper.find('.run-spin').exists()).toBe(false);
    await vi.advanceTimersByTimeAsync(6000);
    expect(mockedFetchRun).toHaveBeenCalledTimes(1);
  });

  it('组件卸载后停止轮询(定时器清理)', async () => {
    vi.useFakeTimers();
    const { wrapper } = await mountSummary('?sessionId=5&runId=7');
    expect(mockedFetchRun).toHaveBeenCalledTimes(1);
    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(6000);
    expect(mockedFetchRun).toHaveBeenCalledTimes(1);
  });
});
