import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { ApiError } from '../api/http';
import { fetchRun, isTerminal, submitRun } from '../api/runs';
import type { RunState, RunSubmitPayload } from '../api/runs';
import RunView from '../views/RunView.vue';

vi.mock('../api/runs', () => ({ fetchRun: vi.fn(), submitRun: vi.fn(), isTerminal: vi.fn() }));
vi.mock('vant', () => ({ showToast: vi.fn() }));
const mockedFetchRun = vi.mocked(fetchRun);
const mockedSubmit = vi.mocked(submitRun);
const mockedIsTerminal = vi.mocked(isTerminal);

/** isTerminal 用真实现(mock 工厂清掉了原模块,统一在 beforeEach 钉冻结语义) */
const TERMINAL = new Set(['DONE', 'FAILED', 'TIMEOUT']);

const PAYLOAD: RunSubmitPayload = {
  cardVersionId: 11, sessionId: 3, nodeId: 6, question: '讲清楚:岳麓书院', level: 'SIMPLE'
};

function runState(patch: Partial<RunState>): RunState {
  return {
    runId: 7, status: 'RUNNING', model: null, latencyMs: null, error: null, artifact: null, ...patch
  };
}

/** 直挂 RunView 的独立 memory 路由;state 经路由 history state 传入(与生产 router.push 一致) */
async function mountRun(id = '7', opts: { state?: Record<string, unknown> } = {}) {
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
  const wrapper = mount(RunView, { global: { plugins: [local] } });
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
    mockedFetchRun.mockResolvedValue(runState({}));
    mockedSubmit.mockResolvedValue({ runId: 42 });
    mockedIsTerminal.mockImplementation((s) => TERMINAL.has(s));
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

  it('轮询:2s 间隔连续 3 次(QUEUED→QUEUED→DONE)后渲染成功卡并停止轮询', async () => {
    vi.useFakeTimers();
    mockedFetchRun
      .mockResolvedValueOnce(runState({ status: 'QUEUED' }))
      .mockResolvedValueOnce(runState({ status: 'QUEUED' }))
      .mockResolvedValueOnce(runState({
        status: 'DONE', model: 'deepseek-chat', latencyMs: 4200,
        artifact: { output: { summary: '…', sections: [] }, sources: {} }
      }));
    const { wrapper } = await mountRun('7', { state: { keRun: JSON.stringify(PAYLOAD) } });
    expect(mockedFetchRun).toHaveBeenCalledTimes(1); // 挂载即首轮
    expect(wrapper.find('.done-card').exists()).toBe(false);

    await vi.advanceTimersByTimeAsync(2000);
    expect(mockedFetchRun).toHaveBeenCalledTimes(2);
    expect(wrapper.find('.done-card').exists()).toBe(false);

    await vi.advanceTimersByTimeAsync(2000);
    await flushPromises();
    expect(mockedFetchRun).toHaveBeenCalledTimes(3);
    // 成功卡:标题 + 「查看讲解」入口(Task 22 接线,当前 disabled)
    expect(wrapper.find('.done-card').exists()).toBe(true);
    expect(wrapper.find('.done-card').text()).toContain('讲解已生成');
    expect(wrapper.find('.done-view').attributes('disabled')).toBeDefined();
    expect(wrapper.find('.run-spin').exists()).toBe(false);
    // 终态后轮询停止:再推进也不调
    await vi.advanceTimersByTimeAsync(6000);
    expect(mockedFetchRun).toHaveBeenCalledTimes(3);
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
