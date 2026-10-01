import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { getCard, fetchCardEntries } from '../api/cards';
import type { CardDetail, CardEntryGroup } from '../api/cards';
import { favorite, unfavorite } from '../api/favorites';
import CompareCard from '../components/CardRenderer/CompareCard.vue';
import TextCard from '../components/CardRenderer/TextCard.vue';
import { useAuthStore } from '../stores/auth';
import CardView from '../views/CardView.vue';

vi.mock('../api/cards', () => ({
  getCard: vi.fn(),
  listPublicCards: vi.fn(),
  fetchCardEntries: vi.fn()
}));
vi.mock('../api/favorites', () => ({
  favorite: vi.fn(),
  unfavorite: vi.fn(),
  listFavorites: vi.fn()
}));
vi.mock('vant', () => ({ showToast: vi.fn() }));
const mockedGet = vi.mocked(getCard);
const mockedEntries = vi.mocked(fetchCardEntries);
const mockedFavorite = vi.mocked(favorite);
const mockedUnfavorite = vi.mocked(unfavorite);

const TEXT_CARD: CardDetail = {
  id: 1,
  theme: 'academy',
  templateType: 'TEXT',
  title: '岳麓书院',
  versionNo: 3,
  updatedAt: '2026-09-30T10:00:00Z',
  favorited: false,
  content: {
    summary: '中国四大书院之一。',
    sections: [{ h: '书院的由来', body: '北宋开宝九年创办。', citations: [1] }],
    related: [{ cardId: 9, relation: '相关联', why: '朱张会讲的人物细节' }]
  },
  sources: [
    { assetId: 11, title: '《岳麓书院史略》', locator: '第一章 p12', license: '已授权' },
    { title: '湖南大学岳麓书院官网', locator: '书院沿革' }
  ]
};

const ENTRIES: CardEntryGroup = {
  cardId: 1,
  defaultEntries: [
    {
      id: 1, name: '为什么建在这里', type: 'AGENT_SERVICE', relationLabel: null,
      targetCardId: null, serviceType: 'EXPLAIN', scope: 'PUBLIC', status: 'ACTIVE', mine: false
    },
    {
      id: 2, name: '哪些人物与这里有关', type: 'LINK_CARD', relationLabel: '相关联',
      targetCardId: 9, serviceType: null, scope: 'PUBLIC', status: 'ACTIVE', mine: false
    }
  ],
  folded: [3, 4, 5].map((n) => ({
    id: n, name: `入口${n}`, type: 'LINK_CARD', relationLabel: '深入了解',
    targetCardId: n + 10, serviceType: null, scope: 'PUBLIC', status: 'ACTIVE', mine: false
  }))
};

/** 直挂 CardView 的独立 memory 路由:/cards/:id 详情 + 相关跳转目标 */
async function mountCard(id = '1', opts: { authed?: boolean } = {}) {
  const pinia = createPinia();
  setActivePinia(pinia);
  if (opts.authed) useAuthStore().token = 'tk';
  const local = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/cards/:id', component: CardView },
      { path: '/cards', component: { render: () => null } },
      { path: '/home', component: { render: () => null } },
      { path: '/login', component: { render: () => null } }
    ]
  });
  await local.push(`/cards/${id}`);
  await local.isReady();
  const wrapper = mount(CardView, { global: { plugins: [pinia, local] } });
  await flushPromises();
  return { wrapper, local };
}

describe('CardView(卡片页,04 §7.2 KCard)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    mockedGet.mockResolvedValue(TEXT_CARD);
    mockedEntries.mockResolvedValue(ENTRIES);
    Element.prototype.scrollIntoView = vi.fn();
  });

  it('TEXT 卡完整渲染:chips/宋体标题/分发正文/出处条数/入口列表', async () => {
    const { wrapper } = await mountCard('1', { authed: true });
    expect(wrapper.text()).toContain('图文卡');
    expect(wrapper.find('h1').text()).toBe('岳麓书院');
    expect(wrapper.findComponent(TextCard).exists()).toBe(true);
    const src = wrapper.find('.src');
    expect(src.exists()).toBe(true);
    expect(src.text()).toContain('《岳麓书院史略》');
    expect(src.text()).toContain('[2]');
    // 入口:default 2 行 + 折叠「还有 3 个入口」+ 新增虚线按钮(规范字面用 i-plus 图标)
    expect(wrapper.findAll('.entry')).toHaveLength(2);
    expect(wrapper.find('.fold').text()).toContain('还有 3 个入口');
    expect(wrapper.find('.addentry').text()).toContain('用一句话新增入口');
    expect(wrapper.find('.addentry use').attributes('href')).toBe('#i-plus');
  });

  it('面包屑 = 专题中文名 · 标题', async () => {
    const { wrapper } = await mountCard();
    expect(wrapper.find('.crumb').text()).toContain('书院地标');
    expect(wrapper.find('.crumb').text()).toContain('岳麓书院');
  });

  it('404 → 空态「卡片不存在或已下架」,不再拉入口', async () => {
    mockedGet.mockResolvedValue(null);
    const { wrapper } = await mountCard('404');
    expect(wrapper.text()).toContain('卡片不存在或已下架');
    expect(wrapper.find('.kcard').exists()).toBe(false);
    expect(mockedEntries).not.toHaveBeenCalled();
  });

  it('COMPARE 卡分发到 CompareCard', async () => {
    mockedGet.mockResolvedValue({
      ...TEXT_CARD,
      templateType: 'COMPARE',
      content: { objects: ['甲', '乙'], dimensions: ['年代'], cells: [['976 年', '1161 年']], citations: [] }
    });
    const { wrapper } = await mountCard();
    expect(wrapper.text()).toContain('对比卡');
    expect(wrapper.findComponent(CompareCard).exists()).toBe(true);
    expect(wrapper.findComponent(TextCard).exists()).toBe(false);
  });

  it('相关联跳转:TextCard emit open → 路由切到 /cards/9 并重拉数据', async () => {
    const { wrapper, local } = await mountCard();
    const calls = mockedGet.mock.calls.length;
    await wrapper.findComponent(TextCard).vm.$emit('open', 9);
    await flushPromises();
    expect(local.currentRoute.value.path).toBe('/cards/9');
    expect(mockedGet.mock.calls.length).toBeGreaterThan(calls);
  });

  it('折叠入口展开后显示全部', async () => {
    const { wrapper } = await mountCard('1', { authed: true });
    await wrapper.find('.fold').trigger('click');
    expect(wrapper.findAll('.entry')).toHaveLength(5);
  });

  it('入口拉取失败:卡主内容仍渲染,入口区局部重试可恢复', async () => {
    mockedEntries.mockRejectedValueOnce(new Error('入口接口超时'));
    const { wrapper } = await mountCard('1', { authed: true });
    // 主内容不受入口失败遮蔽
    expect(wrapper.findComponent(TextCard).exists()).toBe(true);
    expect(wrapper.text()).toContain('岳麓书院');
    expect(wrapper.text()).toContain('入口加载失败,点击重试');
    // 局部重试(底层实现已由 beforeEach 复位为成功)恢复入口列表
    await wrapper.find('.retry-entry').trigger('click');
    await flushPromises();
    expect(wrapper.findAll('.entry')).toHaveLength(2);
    expect(wrapper.text()).not.toContain('入口加载失败');
  });

  it('匿名(无 token):不调入口接口,给出登录引导', async () => {
    const { wrapper } = await mountCard();
    expect(mockedEntries).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('登录后查看这张卡的探索入口');
  });

  it('已登录:调 GET /cards/{id}/entries 渲染入口', async () => {
    const { wrapper } = await mountCard('1', { authed: true });
    expect(mockedEntries).toHaveBeenCalledWith(1);
    expect(wrapper.findAll('.entry')).toHaveLength(2);
  });

  it('点角标 [2] → 出处清单第 2 行高亮并滚动到位,CitationPopover 浮出对应出处', async () => {
    const { wrapper } = await mountCard('1', { authed: true });
    await wrapper.findComponent(TextCard).vm.$emit('cite', 2);
    await flushPromises();
    // 浮层:渲染对应 source([2] + 题名 + 定位)
    const pop = wrapper.find('.cite-pop');
    expect(pop.exists()).toBe(true);
    expect(pop.text()).toContain('[2]');
    expect(pop.text()).toContain('湖南大学岳麓书院官网');
    expect(pop.text()).toContain('书院沿革');
    // 清单对应行:高亮类 + scrollIntoView 滚动到位
    const hl = wrapper.find('.row-hl');
    expect(hl.exists()).toBe(true);
    expect(hl.text()).toContain('[2] 湖南大学岳麓书院官网');
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1);
  });

  it('收藏按钮:匿名点击 → toast「登录后可收藏」跳 /login,不调 API', async () => {
    const { wrapper, local } = await mountCard();
    await wrapper.find('.fav-btn').trigger('click');
    await flushPromises();
    const { showToast } = await import('vant');
    expect(showToast).toHaveBeenCalledWith('登录后可收藏');
    expect(local.currentRoute.value.path).toBe('/login');
    expect(mockedFavorite).not.toHaveBeenCalled();
    expect(mockedUnfavorite).not.toHaveBeenCalled();
  });

  it('已登录点击收藏:调 API 并切换星标实心态', async () => {
    mockedFavorite.mockResolvedValue({ favorited: true });
    mockedUnfavorite.mockResolvedValue({ favorited: false });
    const { wrapper } = await mountCard('1', { authed: true });
    expect(wrapper.find('.fav-btn').classes()).not.toContain('faved');
    await wrapper.find('.fav-btn').trigger('click');
    await flushPromises();
    expect(mockedFavorite).toHaveBeenCalledWith(1);
    expect(wrapper.find('.fav-btn').classes()).toContain('faved');
    // 再点取消:调 unfavorite,星标回落空心
    await wrapper.find('.fav-btn').trigger('click');
    await flushPromises();
    expect(mockedUnfavorite).toHaveBeenCalledWith(1);
    expect(wrapper.find('.fav-btn').classes()).not.toContain('faved');
  });

  it('详情 favorited=true:初态即实心,无需点击', async () => {
    mockedGet.mockResolvedValue({ ...TEXT_CARD, favorited: true });
    const { wrapper } = await mountCard('1', { authed: true });
    expect(wrapper.find('.fav-btn').classes()).toContain('faved');
    expect(mockedFavorite).not.toHaveBeenCalled();
  });
});
