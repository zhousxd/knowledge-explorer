import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchRun, isTerminal, submitRun } from '../api/runs';
import type { RunState } from '../api/runs';

/**
 * Phase 5 Task 21 冻结契约(以 RunController/ExplainService 为准):
 * POST /api/agent/runs → 202 {runId};GET /api/agent/runs/{id} → RunView。
 * jackson non_null 序列化会把 null 键整键省略 → wire 层归一为 null 家族
 * (Phase 4 终审 seam 钉子,参照 sessions.spec parentNodeId 归一先例)。
 */
function jsonResp(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response;
}

/** DONE 全键样本(artifact 形状 = ExplainResult content_json:output 嵌套 + sources/disclaimer/audit) */
const DONE_RUN: RunState = {
  runId: 7,
  status: 'DONE',
  model: 'deepseek-chat',
  latencyMs: 4200,
  error: null,
  artifact: {
    output: {
      summary: '岳麓书院创办于北宋…',
      sections: [{ body: '朱张会讲…', claimType: 'FACT', citations: [11] }],
      openQuestions: ['书院经费从何而来?'],
      evidenceGaps: []
    },
    sources: { '11': '《岳麓书院史略》第一章' },
    disclaimer: '本内容由 AI 生成,仅供参考',
    audit: { stripped: 1, filtered: 0 }
  }
};

describe('submitRun 契约', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('POST /api/agent/runs 带全量 payload 并解包 202 信封得 {runId}', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResp(202, { code: 0, message: 'ok', traceId: 't', data: { runId: 42 } }));
    vi.stubGlobal('fetch', fetchMock);

    const payload = { cardVersionId: 11, sessionId: 3, nodeId: 6, question: '讲清楚:岳麓书院', level: 'SIMPLE' as const };
    await expect(submitRun(payload)).resolves.toEqual({ runId: 42 });
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/agent/runs');
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1].body)).toEqual(payload);
  });

  it('追问提交(FR-E08):payload 带 parentRunId 原样透传(后端校验存在且属主)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResp(202, { code: 0, message: 'ok', traceId: 't', data: { runId: 43 } }));
    vi.stubGlobal('fetch', fetchMock);

    const payload = {
      cardVersionId: 11, sessionId: 3, nodeId: 6,
      question: '那经费从哪来?', level: 'SIMPLE' as const, parentRunId: 7
    };
    await expect(submitRun(payload)).resolves.toEqual({ runId: 43 });
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1].body)).toEqual(payload);
  });

  it('429 配额超限:ApiError code/message 原样透传给调用方展示(04 §8.6 envelope)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResp(429, { code: 429, message: '今日 30 次智能服务已用完,明早 8 点恢复', traceId: 't9', data: null })));

    await expect(submitRun({
      cardVersionId: 11, sessionId: 3, nodeId: 6, question: '讲清楚:岳麓书院', level: 'SIMPLE'
    })).rejects.toMatchObject({ code: 429, message: '今日 30 次智能服务已用完,明早 8 点恢复' });
  });
});

describe('fetchRun 轮询契约', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('GET /api/agent/runs/{id} 解包信封并归一 null 家族', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResp(200, { code: 0, message: 'ok', traceId: 't', data: DONE_RUN }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchRun(7)).resolves.toEqual(DONE_RUN);
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/agent/runs/7');
  });

  /**
   * Phase 4 终审 seam 钉子(参照 P4-16 parentNodeId 归一先例):jackson non_null 把
   * null 键整键省略 —— QUEUED/RUNNING 的 model/latencyMs/error/artifact、DONE 但无 artifact
   * (无会话 run,P5-18 决策)都会缺键。fixture 用 delete 模拟真实序列化(而非显式 null),
   * 不归一则运行态把 undefined 当异常、结果页把 missing artifact 当可渲染。
   */
  it('wire 归一:model/latencyMs/error/artifact 缺键(non_null)→ 归一为 null', async () => {
    const raw = {
      runId: 8,
      status: 'RUNNING'
      // model/latencyMs/error/artifact 整键缺失
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResp(200, { code: 0, message: 'ok', traceId: 't', data: raw })));

    const run = await fetchRun(8);
    expect(run.status).toBe('RUNNING');
    expect(run.model).toBeNull();
    expect(run.latencyMs).toBeNull();
    expect(run.error).toBeNull();
    expect(run.artifact).toBeNull();
  });

  it('wire 归一:DONE 但 error 缺键、artifact 仅部分字段(敏感链裁剪)→ 缺处归 null 不虚报', async () => {
    const raw: Record<string, unknown> = {
      runId: 9,
      status: 'DONE',
      latencyMs: 3800
      // model/error 整键缺失;artifact 存在但 audit 被 non_null 省略
    };
    raw.artifact = {
      output: { summary: '…', sections: [] },
      sources: {}
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResp(200, { code: 0, message: 'ok', traceId: 't', data: raw })));

    const run = await fetchRun(9);
    expect(run.model).toBeNull();
    expect(run.error).toBeNull();
    expect(run.artifact?.audit).toBeUndefined(); // 嵌套可选字段按可选透传,不伪造空对象
    expect(run.artifact?.output?.summary).toBe('…');
  });

  it('isTerminal:DONE/FAILED/TIMEOUT 为终态,QUEUED/RUNNING 继续', () => {
    expect(isTerminal('DONE')).toBe(true);
    expect(isTerminal('FAILED')).toBe(true);
    expect(isTerminal('TIMEOUT')).toBe(true);
    expect(isTerminal('QUEUED')).toBe(false);
    expect(isTerminal('RUNNING')).toBe(false);
  });
});
