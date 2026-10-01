import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchLatestSession, fetchMySessions, fetchSessionTree } from '../api/sessions';
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
});
