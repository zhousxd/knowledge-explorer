import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createSession } from '../api/sessions';
import { useSessionStore } from '../stores/sessionStore';

vi.mock('../api/sessions', () => ({ createSession: vi.fn() }));
const mockedCreate = vi.mocked(createSession);

/** Task 21:卡片页服务键前置 —— 无会话则建(theme=卡专题,goal 置空),已有则复用不重建 */
describe('sessionStore.ensureForCard', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it('无会话:调 createSession(theme,goal=null) 落库并本地记住', async () => {
    mockedCreate.mockResolvedValue({ sessionId: 12 });

    const store = useSessionStore();
    await expect(store.ensureForCard({ theme: 'academy' })).resolves.toBe(12);

    expect(mockedCreate).toHaveBeenCalledTimes(1);
    expect(mockedCreate).toHaveBeenCalledWith('academy', null);
    expect(store.sessionId).toBe(12);
    expect(store.theme).toBe('academy');
  });

  it('已有会话:直接复用,不再调 createSession(跨卡点服务键同会话)', async () => {
    mockedCreate.mockResolvedValue({ sessionId: 12 });

    const store = useSessionStore();
    await store.ensureForCard({ theme: 'academy' });
    await expect(store.ensureForCard({ theme: 'cuisine' })).resolves.toBe(12);

    expect(mockedCreate).toHaveBeenCalledTimes(1);
    expect(store.theme).toBe('academy'); // 首建主题不覆盖
  });

  it('createSession 失败:错误原样抛出由视图 toast,本地不落脏会话号', async () => {
    mockedCreate.mockRejectedValue(new Error('网络异常'));

    const store = useSessionStore();
    await expect(store.ensureForCard({ theme: 'academy' })).rejects.toThrow('网络异常');
    expect(store.sessionId).toBeNull();
    // 失败不记住:下次再点仍会尝试新建
    mockedCreate.mockResolvedValue({ sessionId: 5 });
    await expect(store.ensureForCard({ theme: 'academy' })).resolves.toBe(5);
  });
});
