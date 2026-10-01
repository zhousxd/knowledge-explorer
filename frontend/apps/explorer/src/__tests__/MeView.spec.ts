import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from '../App.vue';
import router from '../router';
import { listFavorites } from '../api/favorites';
import type { FavoritePage } from '../api/favorites';
import { fetchMyEntries } from '../api/entries';
import type { MineEntryItem } from '../api/entries';
import { fetchMySessions, fetchLatestSession } from '../api/sessions';
import { fetchMyQuota } from '../api/me';
import type { QuotaView } from '../api/me';

// 只覆写本页用到的取数口,其余导出走原实现(路由树懒 import 的兄弟视图不受影响)
vi.mock('../api/favorites', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/favorites')>()),
  listFavorites: vi.fn()
}));
vi.mock('../api/entries', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/entries')>()),
  fetchMyEntries: vi.fn()
}));
vi.mock('../api/sessions', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/sessions')>()),
  fetchMySessions: vi.fn(),
  // /me 用例点击路径卡会真跳 /path:PathView 挂载即拉 latest,null → 空态即可
  fetchLatestSession: vi.fn()
}));
vi.mock('../api/me', () => ({ fetchMyQuota: vi.fn() }));

const mockedFavs = vi.mocked(listFavorites);
const mockedEntries = vi.mocked(fetchMyEntries);
const mockedSessions = vi.mocked(fetchMySessions);
const mockedLatest = vi.mocked(fetchLatestSession);
const mockedQuota = vi.mocked(fetchMyQuota);

const FAV_PAGE: FavoritePage = {
  items: [
    {
      cardId: 11,
      title: '岳麓书院：从选址到人物',
      theme: 'academy',
      templateType: 'TEXT',
      summaryText: '千年学府',
      favoritedAt: '2026-09-30T10:00:00+08:00'
    },
    {
      cardId: 12,
      title: '书院与山寺',
      theme: 'sound',
      templateType: 'TEXT',
      summaryText: null,
      favoritedAt: '2026-09-29T10:00:00+08:00'
    }
  ],
  total: 2,
  page: 1,
  size: 20
};

const MY_ENTRIES: MineEntryItem[] = [
  {
    id: 21,
    name: '讲讲岳麓书院',
    type: 'AGENT_SERVICE',
    serviceType: 'EXPLAIN',
    relationLabel: null,
    targetCardId: null,
    scope: 'PRIVATE',
    status: 'ACTIVE',
    testTotal: 3,
    cardId: 11,
    cardTitle: '岳麓书院：从选址到人物'
  },
  {
    id: 22,
    name: '书院对比',
    type: 'AGENT_SERVICE',
    serviceType: 'COMPARE',
    relationLabel: null,
    targetCardId: null,
    scope: 'PUBLIC',
    status: 'PENDING',
    testTotal: 0,
    cardId: 12,
    cardTitle: '书院与山寺'
  }
];

/** resetAt=次日零点(后端 QuotaController 语义) */
const QUOTA_OPEN: QuotaView = { used: 8, limit: 30, remaining: 22, resetAt: '2026-10-01T00:00:00+08:00' };

/** 经真实路由宿主进 /me(守卫要求登录态);单例路由先去 /login 强制重新导航 */
async function gotoMe(): Promise<ReturnType<typeof mount>> {
  localStorage.setItem('ke_ex_token', 'tok');
  const pinia = createPinia();
  setActivePinia(pinia);
  await router.push('/login');
  await router.push('/me');
  const wrapper = mount(App, { global: { plugins: [pinia, router] } });
  await flushPromises();
  return wrapper;
}

describe('MeView(个人空间,FR-U02/U05)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    mockedFavs.mockResolvedValue(FAV_PAGE);
    mockedEntries.mockResolvedValue(MY_ENTRIES);
    mockedSessions.mockResolvedValue({ items: [], total: 3, page: 1, size: 1 });
    mockedLatest.mockResolvedValue(null);
    mockedQuota.mockResolvedValue(QUOTA_OPEN);
  });

  it('四入口计数渲染:收藏 total/路径总数(size=1 取 total)/入口数,配额卡并列', async () => {
    const wrapper = await gotoMe();

    // 各源取数:收藏 size=20 带清单;路径 size=1 只取 total;入口全量;配额一次
    expect(mockedFavs).toHaveBeenCalledWith(1, 20);
    expect(mockedSessions).toHaveBeenCalledWith(1, 1);
    expect(mockedEntries).toHaveBeenCalledTimes(1);
    expect(mockedQuota).toHaveBeenCalledTimes(1);

    const counts = wrapper.findAll('.c-count').map((c) => c.text());
    expect(counts).toEqual(['2', '3', '2']); // 收藏 / 路径 / 私人入口(路径与成果卡无计数)
    expect(wrapper.find('[data-testid="quota"]').exists()).toBe(true);
  });

  it('收藏列表展开:行带标题/专题 chip,点击行跳 /cards/{id}', async () => {
    const wrapper = await gotoMe();

    // 收起态不渲染清单
    expect(wrapper.find('[data-card-id="11"]').exists()).toBe(false);
    await wrapper.find('[data-testid="fav-toggle"]').trigger('click');
    const rows = wrapper.findAll('[data-card-id]');
    expect(rows).toHaveLength(2);
    expect(rows[0]?.text()).toContain('岳麓书院：从选址到人物');
    expect(rows[0]?.find('.r-chip').text()).toBe('academy');

    await rows[0]!.trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/cards/11');
  });

  it('私人入口列表展开:名称/状态 chip(ACTIVE=可用,PENDING=审核中)', async () => {
    const wrapper = await gotoMe();
    await wrapper.find('[data-testid="entry-toggle"]').trigger('click');

    const chips = wrapper.findAll('.r-chip[data-status]');
    expect(chips.map((c) => c.text())).toEqual(['可用', '审核中']);
    expect(wrapper.text()).toContain('讲讲岳麓书院');
    expect(wrapper.text()).toContain('岳麓书院：从选址到人物'); // 行副题=所属卡题
  });

  it('配额未满:进度条主色按 8/30 填充,提示剩余次数与重置时间', async () => {
    const wrapper = await gotoMe();

    const fill = wrapper.find('[data-testid="quota-fill"]');
    expect(fill.classes()).not.toContain('is-full');
    expect(fill.attributes('style')).toContain('26.66');
    expect(wrapper.find('[data-testid="quota-hint"]').text()).toBe('剩余 22 次 · 10月1日 00:00 重置');
  });

  it('配额已满:进度条与计数变警示色,提示已用完与恢复时间(04 §8.6)', async () => {
    mockedQuota.mockResolvedValue({ used: 30, limit: 30, remaining: 0, resetAt: '2026-10-01T00:00:00+08:00' });
    const wrapper = await gotoMe();

    const fill = wrapper.find('[data-testid="quota-fill"]');
    expect(fill.classes()).toContain('is-full');
    expect(fill.attributes('style')).toContain('100%');
    expect(wrapper.find('.q-num').classes()).toContain('is-full');
    expect(wrapper.find('[data-testid="quota-hint"]').text()).toBe('今日 30 次智能服务已用完,10月1日 00:00 恢复');
  });

  it('路径/路径与成果两卡均跳 /path(整理在路径内)', async () => {
    const wrapper = await gotoMe();

    await wrapper.find('[data-testid="path-card"]').trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/path');

    // 回 /me 再走「路径与成果」卡
    await router.push('/me');
    await flushPromises();
    await wrapper.find('[data-testid="summary-card"]').trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/path');
  });

  it('配额加载失败:配额卡显示失败提示,四入口计数不受影响', async () => {
    mockedQuota.mockRejectedValue(new Error('network'));
    const wrapper = await gotoMe();

    expect(wrapper.find('[data-testid="quota"]').text()).toContain('配额加载失败');
    expect(wrapper.findAll('.c-count').map((c) => c.text())).toEqual(['2', '3', '2']);
  });
});
