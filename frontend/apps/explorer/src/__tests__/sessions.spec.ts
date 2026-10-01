import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchLatestSession } from '../api/sessions';

function jsonResp(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response;
}

/** P3-13 遗留补测:端点 Phase 4 Task 16 才交付,先冻结 404 归一契约 */
describe('fetchLatestSession 契约', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('404(端点未交付/无会话)归一为 null,调用方据以隐藏继续探索卡', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValue(jsonResp(404, { code: 404, message: 'Not Found', traceId: 't4', data: null })));

    await expect(fetchLatestSession()).resolves.toBeNull();
  });
});
