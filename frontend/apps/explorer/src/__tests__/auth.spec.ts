import { flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../stores/auth';
import router from '../router';

function jsonResp(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response;
}

describe('auth store(探索端,ke_ex_* 键)', () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
  });

  it('sendCode 调 /api/auth/sms/send', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValue(jsonResp(200, { code: 0, message: 'ok', traceId: 't0', data: null }));
    vi.stubGlobal('fetch', fetchMock);

    const store = useAuthStore();
    await store.sendCode('13900000001');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/sms/send',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ phone: '13900000001' }) })
    );
  });

  it('loginByCode 成功后写入 state 与 ke_ex_* localStorage', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResp(200, {
        code: 0, message: 'ok', traceId: 't1',
        data: { accessToken: 'at-1', refreshToken: 'rt-1' }
      }))
      .mockResolvedValueOnce(jsonResp(200, {
        code: 0, message: 'ok', traceId: 't2',
        data: { id: 7, nickname: '探索者0001', role: 'EXPLORER' }
      }));
    vi.stubGlobal('fetch', fetchMock);

    const store = useAuthStore();
    await store.loginByCode('13900000001', '246810');

    expect(store.token).toBe('at-1');
    expect(store.refreshToken).toBe('rt-1');
    expect(store.user).toEqual({ id: 7, nickname: '探索者0001', role: 'EXPLORER' });
    expect(localStorage.getItem('ke_ex_token')).toBe('at-1');
    expect(localStorage.getItem('ke_ex_refresh')).toBe('rt-1');
    expect(JSON.parse(localStorage.getItem('ke_ex_user') as string)).toEqual({ id: 7, nickname: '探索者0001', role: 'EXPLORER' });
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/auth/sms/login');
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({ body: JSON.stringify({ phone: '13900000001', code: '246810' }) });
    // /me 请求带上新落的 Bearer
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ headers: { Authorization: 'Bearer at-1' } });
  });

  it('login(密码) 成功后写入 ke_ex_* localStorage', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResp(200, {
        code: 0, message: 'ok', traceId: 't3',
        data: { accessToken: 'at-2', refreshToken: 'rt-2' }
      }))
      .mockResolvedValueOnce(jsonResp(200, {
        code: 0, message: 'ok', traceId: 't4',
        data: { id: 8, nickname: '老用户', role: 'EXPLORER' }
      }));
    vi.stubGlobal('fetch', fetchMock);

    const store = useAuthStore();
    await store.login('13800000000', 'pw');

    expect(store.token).toBe('at-2');
    expect(localStorage.getItem('ke_ex_token')).toBe('at-2');
    expect(localStorage.getItem('ke_ex_refresh')).toBe('rt-2');
    expect(JSON.parse(localStorage.getItem('ke_ex_user') as string)).toEqual({ id: 8, nickname: '老用户', role: 'EXPLORER' });
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/auth/login');
  });

  it('401 响应清除本地凭证并跳转登录页', async () => {
    localStorage.setItem('ke_ex_token', 'old-token');
    localStorage.setItem('ke_ex_refresh', 'old-refresh');
    localStorage.setItem('ke_ex_user', JSON.stringify({ id: 1, nickname: '旧人', role: 'EXPLORER' }));
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValue(jsonResp(401, { code: 401, message: '登录已失效', traceId: 't9', data: null })));

    const store = useAuthStore();
    store.restore();
    expect(store.token).toBe('old-token');

    await expect(store.login('1', '2')).rejects.toMatchObject({ code: 401 });
    await flushPromises();

    expect(store.token).toBe('');
    expect(store.user).toBeNull();
    expect(localStorage.getItem('ke_ex_token')).toBeNull();
    expect(localStorage.getItem('ke_ex_refresh')).toBeNull();
    expect(localStorage.getItem('ke_ex_user')).toBeNull();
    expect(router.currentRoute.value.path).toBe('/login');
  });

  it('restore 从 localStorage 恢复会话', () => {
    localStorage.setItem('ke_ex_token', 'tk');
    localStorage.setItem('ke_ex_refresh', 'rk');
    localStorage.setItem('ke_ex_user', JSON.stringify({ id: 2, nickname: '探索者9999', role: 'EXPLORER' }));

    const store = useAuthStore();
    store.restore();

    expect(store.token).toBe('tk');
    expect(store.refreshToken).toBe('rk');
    expect(store.user).toEqual({ id: 2, nickname: '探索者9999', role: 'EXPLORER' });
  });
});
