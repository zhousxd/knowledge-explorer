import { flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../stores/auth';
import router from '../router';

function jsonResp(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response;
}

describe('auth store', () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
  });

  it('login 成功后写入 state 与 localStorage', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResp(200, {
        code: 0, message: 'ok', traceId: 't1',
        data: { accessToken: 'at-1', refreshToken: 'rt-1' }
      }))
      .mockResolvedValueOnce(jsonResp(200, {
        code: 0, message: 'ok', traceId: 't2',
        data: { id: 7, nickname: '阿珍', role: 'EDITOR' }
      }));
    vi.stubGlobal('fetch', fetchMock);

    const store = useAuthStore();
    await store.login('13800000000', 'pw');

    expect(store.token).toBe('at-1');
    expect(store.refreshToken).toBe('rt-1');
    expect(store.user).toEqual({ id: 7, nickname: '阿珍', role: 'EDITOR' });
    expect(localStorage.getItem('ke_wb_token')).toBe('at-1');
    expect(localStorage.getItem('ke_wb_refresh')).toBe('rt-1');
    expect(JSON.parse(localStorage.getItem('ke_wb_user') as string)).toEqual({ id: 7, nickname: '阿珍', role: 'EDITOR' });
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ headers: { Authorization: 'Bearer at-1' } });
  });

  it('401 响应清除本地凭证并跳转登录页', async () => {
    localStorage.setItem('ke_wb_token', 'old-token');
    localStorage.setItem('ke_wb_refresh', 'old-refresh');
    localStorage.setItem('ke_wb_user', JSON.stringify({ id: 1, nickname: '旧人', role: 'OPERATOR' }));
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValue(jsonResp(401, { code: 401, message: '登录已失效', traceId: 't9', data: null })));

    const store = useAuthStore();
    store.restore();
    expect(store.token).toBe('old-token');

    await expect(store.login('1', '2')).rejects.toMatchObject({ code: 401 });
    await flushPromises();

    expect(store.token).toBe('');
    expect(store.user).toBeNull();
    expect(localStorage.getItem('ke_wb_token')).toBeNull();
    expect(localStorage.getItem('ke_wb_refresh')).toBeNull();
    expect(localStorage.getItem('ke_wb_user')).toBeNull();
    expect(router.currentRoute.value.path).toBe('/login');
  });

  it('restore 从 localStorage 恢复会话', () => {
    localStorage.setItem('ke_wb_token', 'tk');
    localStorage.setItem('ke_wb_refresh', 'rk');
    localStorage.setItem('ke_wb_user', JSON.stringify({ id: 2, nickname: '运营者', role: 'OPERATOR' }));

    const store = useAuthStore();
    store.restore();

    expect(store.token).toBe('tk');
    expect(store.refreshToken).toBe('rk');
    expect(store.user).toEqual({ id: 2, nickname: '运营者', role: 'OPERATOR' });
  });

  it('logout 清空 state 与 localStorage 并回到登录页', async () => {
    localStorage.setItem('ke_wb_token', 'tk');
    localStorage.setItem('ke_wb_user', JSON.stringify({ id: 2, nickname: '运营者', role: 'OPERATOR' }));

    const store = useAuthStore();
    store.restore();
    await store.logout();
    await flushPromises();

    expect(store.token).toBe('');
    expect(store.user).toBeNull();
    expect(localStorage.getItem('ke_wb_token')).toBeNull();
    expect(router.currentRoute.value.path).toBe('/login');
  });
});
