import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, http, setUnauthorizedHandler } from '../api/http';

function jsonResp(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response;
}

describe('http 封装', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('get 解包 envelope 返回 data,并附带 Bearer 头', async () => {
    localStorage.setItem('ke_ex_token', 'tk1');
    const fetchMock = vi.fn()
      .mockResolvedValue(jsonResp(200, { code: 0, message: 'ok', traceId: 't1', data: { hello: 'world' } }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(http.get('/me')).resolves.toEqual({ hello: 'world' });
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/me',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer tk1' }) })
    );
  });

  it('post 发送 JSON body', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValue(jsonResp(200, { code: 0, message: 'ok', traceId: 't2', data: null }));
    vi.stubGlobal('fetch', fetchMock);

    await http.post('/auth/sms/login', { phone: '138', code: '246810' });
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/sms/login',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ phone: '138', code: '246810' }) })
    );
  });

  it('post FormData(文件上传)原样发送,headers 不含 Content-Type(交给浏览器带 boundary)', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValue(jsonResp(200, { code: 0, message: 'ok', traceId: 't3', data: { imported: 1 } }));
    vi.stubGlobal('fetch', fetchMock);

    const form = new FormData();
    form.append('file', new File(['kind,title\nbook,书'], 'assets.csv', { type: 'text/csv' }));
    await expect(http.post('/api/form-target', form)).resolves.toEqual({ imported: 1 });

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(init.body).toBe(form);
    const headers = init.headers as Record<string, string>;
    expect(Object.keys(headers)).not.toContain('Content-Type');
  });

  it('code != 0 抛 ApiError 并透传 traceId(429 频控同路)', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValue(jsonResp(200, { code: 429, message: '发送过于频繁', traceId: 'tr-9', data: null })));

    await expect(http.post('/auth/sms/send', { phone: '13800000000' }))
      .rejects.toMatchObject({ code: 429, message: '发送过于频繁', traceId: 'tr-9' });
  });

  it('网络失败抛 ApiError(-1 网络异常)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('boom')));

    await expect(http.get('/me')).rejects.toMatchObject({ code: -1, message: '网络异常' });
  });

  it('401 触发注入的 onUnauthorized 回调并抛 401 ApiError', async () => {
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValue(jsonResp(401, { code: 401, message: '登录已失效', traceId: 't401', data: null })));

    await expect(http.get('/me')).rejects.toMatchObject({ code: 401, traceId: 't401' });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('ApiError 是 Error 子类', () => {
    const err = new ApiError(500, '服务异常', 'tx');
    expect(err).toBeInstanceOf(Error);
    expect(err.name).toBe('ApiError');
    expect(err.message).toBe('服务异常');
  });
});
