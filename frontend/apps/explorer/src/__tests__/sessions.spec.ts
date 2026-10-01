import { afterEach, describe, expect, it, vi } from 'vitest';
import { addNode, fetchLatestSession, fetchMySessions, fetchOpenQuestions, fetchSessionTree } from '../api/sessions';
import type { ResumeSession, SessionPage, SessionTree } from '../api/sessions';

function jsonResp(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response;
}

/** P4-16 冻结契约(以 SessionController/SessionService 为准)的形状样本 */
const LATEST: ResumeSession = {
  sessionId: 3,
  title: '岳麓书院：从选址到人物',
  lastVisitedAt: '2026-09-29T21:00:00Z',
  nodeCount: 4,
  branchCount: 1,
  openQuestionCount: 0
};

describe('fetchLatestSession 契约', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('404(无会话)归一为 null,调用方据以隐藏继续探索卡/显示路径空态', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValue(jsonResp(404, { code: 404, message: '暂无可续探的会话', traceId: 't4', data: null })));

    await expect(fetchLatestSession()).resolves.toBeNull();
  });

  it('200 透传 P4-16 冻结 ResumeSession 新形状(sessionId/lastVisitedAt/nodeCount/branchCount/openQuestionCount)', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValue(jsonResp(200, { code: 0, message: 'ok', traceId: 't', data: LATEST })));

    await expect(fetchLatestSession()).resolves.toEqual(LATEST);
  });

  it('列表与树端点按冻结路径请求(query/PathVariable)并解包信封', async () => {
    const PAGE: SessionPage = {
      items: [{
        sessionId: 1, theme: 'academy', title: '岳麓书院', goal: '读懂书院',
        nodeCount: 3, branchCount: 0, lastVisitedAt: '2026-09-29T21:00:00Z', status: 'ACTIVE'
      }],
      total: 12, page: 2, size: 5
    };
    const TREE: SessionTree = {
      sessionId: 9, theme: 'academy', goal: '读懂书院', explainLevel: 'DEEP', status: 'ACTIVE',
      createdAt: '2026-09-28T09:00:00Z', updatedAt: '2026-09-29T21:00:00Z',
      nodes: [{
        nodeId: 1, parentNodeId: null, cardVersionId: 11, entryId: null, questionText: null,
        isNewKnowledge: true, visitedAt: '2026-09-28T09:00:00Z', cardTitle: '岳麓书院：从选址到人物'
      }]
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResp(200, { code: 0, message: 'ok', traceId: 't', data: PAGE }))
      .mockResolvedValueOnce(jsonResp(200, { code: 0, message: 'ok', traceId: 't', data: TREE }));
    vi.stubGlobal('fetch', fetchMock);

    const page = await fetchMySessions(2, 5);
    expect(page.total).toBe(12);
    expect(page.items[0]?.sessionId).toBe(1);
    expect(page.items[0]?.lastVisitedAt).toBe('2026-09-29T21:00:00Z');

    const tree = await fetchSessionTree(9);
    expect(tree.explainLevel).toBe('DEEP');
    expect(tree.nodes[0]?.nodeId).toBe(1);
    expect(tree.nodes[0]?.parentNodeId).toBeNull();

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/sessions?page=2&size=5');
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/sessions/9');
  });

  /**
   * 回归(Phase 4 终审 Critical):jackson non_null 把根节点 parentNodeId=null 整键省略,
   * 树渲染以 null 为根键 —— 不归一则真实 API 下 /path 渲染空树。fixture 用 delete 模拟真实序列化
   * (而非显式 null);theme/goal 同族空值也整键缺失。放在本 spec 的原因:PathView/path spec
   * 整体 mock ../api/sessions,deleted-key fixture 会绕过 api 层归一;此处走真实 fetchSessionTree,
   * 归一后的 null 根键由既有 store/PathView 渲染测试(childrenMap.get(null))衔接保证成树。
   */
  it('wire 归一:根节点 parentNodeId 键被省略(non_null)→ 归一为 null,theme/goal 缺键不虚报必有', async () => {
    const raw = {
      sessionId: 9,
      explainLevel: 'DEEP',
      status: 'ACTIVE',
      createdAt: '2026-09-28T09:00:00Z',
      updatedAt: '2026-09-29T21:00:00Z',
      nodes: [
        { nodeId: 1, cardVersionId: 11, entryId: null, questionText: null,
          isNewKnowledge: true, visitedAt: '2026-09-28T09:00:00Z', cardTitle: '岳麓书院：从选址到人物' },
        { nodeId: 2, parentNodeId: 1, cardVersionId: null, entryId: null, questionText: '为什么建在这里',
          isNewKnowledge: false, visitedAt: '2026-09-29T21:00:00Z', cardTitle: null }
      ]
    };
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValue(jsonResp(200, { code: 0, message: 'ok', traceId: 't', data: raw })));

    const tree = await fetchSessionTree(9);
    expect(tree.theme).toBeUndefined();
    expect(tree.goal).toBeUndefined();
    const [root, child] = tree.nodes;
    expect(root?.parentNodeId).toBeNull(); // 归一后根键=null → childrenMap.get(null) 命中根节点
    expect(child?.parentNodeId).toBe(1);
  });

  it('addNode 响应同族归一:挂根(缺省 parentNodeId)时缺键归一为 null', async () => {
    const raw = { nodeId: 6, cardVersionId: 77, entryId: null, questionText: null,
      isNewKnowledge: true, visitedAt: '2026-09-30T10:00:00Z', cardTitle: '根上新卡' };
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValue(jsonResp(200, { code: 0, message: 'ok', traceId: 't', data: raw })));

    const created = await addNode(9, { cardVersionId: 77 });
    expect(created.parentNodeId).toBeNull();
  });
});

describe('fetchOpenQuestions 契约(FR-E09 / Task 24 冻结)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('GET /api/sessions/{id}/open-questions 解包信封得 {questions:[{runId,question,collectedAt}]}', async () => {
    const QUESTIONS = {
      questions: [
        { runId: 42, question: '书院经费从何而来?', collectedAt: '2026-09-30T09:30:00Z' },
        { runId: 41, question: '朱张会讲是谁主持的?', collectedAt: '2026-09-29T18:00:00Z' }
      ]
    };
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResp(200, { code: 0, message: 'ok', traceId: 't', data: QUESTIONS }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchOpenQuestions(5)).resolves.toEqual(QUESTIONS);
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/sessions/5/open-questions');
  });
});
