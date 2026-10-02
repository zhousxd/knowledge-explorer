import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createSession, fetchLatestSession, updateExplainLevel } from '../api/sessions';
import { useSessionStore } from '../stores/sessionStore';

vi.mock('../api/sessions', () => ({
  createSession: vi.fn(),
  updateExplainLevel: vi.fn(),
  fetchLatestSession: vi.fn()
}));
const mockedCreate = vi.mocked(createSession);
const mockedUpdate = vi.mocked(updateExplainLevel);
const mockedLatest = vi.mocked(fetchLatestSession);

/** Task 21:卡片页服务键前置 —— 无会话则建(theme=卡专题,goal 置空),已有则复用不重建 */
describe('sessionStore.ensureForCard', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it('无同专题会话(latest 404):调 createSession(theme,goal=null) 落库并本地记住', async () => {
    mockedLatest.mockResolvedValue(null);
    mockedCreate.mockResolvedValue({ sessionId: 12 });
    const store = useSessionStore();
    await expect(store.ensureForCard({ theme: 'academy' })).resolves.toBe(12);
    expect(mockedCreate).toHaveBeenCalledTimes(1);
    expect(mockedCreate).toHaveBeenCalledWith('academy', null);
    expect(store.sessionId).toBe(12);
    expect(store.theme).toBe('academy');
  });

  it('同专题最新 ACTIVE 会话:断点续探复用不新建(FR-E01/A1 同会话语义)', async () => {
    mockedLatest.mockResolvedValue({ sessionId: 30, theme: 'academy', title: 'x', lastVisitedAt: '2026-10-01T00:00:00Z', nodeCount: 2, branchCount: 0, openQuestionCount: 0 });
    const store = useSessionStore();
    await expect(store.ensureForCard({ theme: 'academy' })).resolves.toBe(30);
    expect(mockedCreate).not.toHaveBeenCalled();
    expect(store.sessionId).toBe(30);
    expect(store.theme).toBe('academy');
  });

  it('跨专题:不复用 latest,新建会话(theme=新卡专题)', async () => {
    mockedLatest.mockResolvedValue({ sessionId: 30, theme: 'academy', title: 'x', lastVisitedAt: '2026-10-01T00:00:00Z', nodeCount: 2, branchCount: 0, openQuestionCount: 0 });
    mockedCreate.mockResolvedValue({ sessionId: 21 });
    const store = useSessionStore();
    await expect(store.ensureForCard({ theme: 'cuisine' })).resolves.toBe(21);
    expect(mockedLatest).toHaveBeenCalledTimes(1);
    expect(mockedCreate).toHaveBeenCalledWith('cuisine', null);
    expect(store.theme).toBe('cuisine');
  });

  it('createSession 失败:错误原样抛出由视图 toast,本地不落脏会话号', async () => {
    mockedLatest.mockResolvedValue(null);
    mockedCreate.mockRejectedValue(new Error('网络异常'));
    const store = useSessionStore();
    await expect(store.ensureForCard({ theme: 'academy' })).rejects.toThrow('网络异常');
    expect(store.sessionId).toBeNull();
    mockedCreate.mockResolvedValue({ sessionId: 5 });
    await expect(store.ensureForCard({ theme: 'academy' })).resolves.toBe(5);
  });
});

/** Task 22:FR-E10 讲解档位会话记忆 —— 切换经 PUT /sessions/{id}/explain-level,响应回填本地 */
describe('sessionStore.changeExplainLevel', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it('初始档位 SIMPLE(与会话创建默认一致)', () => {
    expect(useSessionStore().explainLevel).toBe('SIMPLE');
  });

  it('切换成功:PUT 会话档位并以响应回填本地', async () => {
    mockedUpdate.mockResolvedValue({ explainLevel: 'DEEP' });

    const store = useSessionStore();
    store.sessionId = 12;
    await store.changeExplainLevel('DEEP');

    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    expect(mockedUpdate).toHaveBeenCalledWith(12, 'DEEP');
    expect(store.explainLevel).toBe('DEEP');
  });

  it('无会话:不调 PUT,直接抛错(调用方 toast 兜底)', async () => {
    const store = useSessionStore();
    await expect(store.changeExplainLevel('DEEP')).rejects.toThrow('会话尚未创建');
    expect(mockedUpdate).not.toHaveBeenCalled();
  });

  it('PUT 失败:错误原样抛出由视图 toast,本地档位不变', async () => {
    mockedUpdate.mockRejectedValue(new Error('讲解度仅支持 SIMPLE/DEEP/CHILD'));

    const store = useSessionStore();
    store.sessionId = 12;
    await expect(store.changeExplainLevel('CHILD')).rejects.toThrow('讲解度仅支持 SIMPLE/DEEP/CHILD');
    expect(store.explainLevel).toBe('SIMPLE');
  });
});
