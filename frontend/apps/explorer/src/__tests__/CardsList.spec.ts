import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { listPublicCards } from '../api/cards';
import type { PublicCardPage } from '../api/cards';
import CardsView from '../views/CardsView.vue';

vi.mock('../api/cards', () => ({
  listPublicCards: vi.fn(),
  getCard: vi.fn(),
  fetchCardEntries: vi.fn()
}));
const mockedList = vi.mocked(listPublicCards);

const PAGE1: PublicCardPage = {
  items: [
    {
      id: 1, theme: 'academy', templateType: 'TEXT', title: '岳麓书院',
      summaryText: '中国四大书院之一，北宋创办于岳麓山下。', sort: 0, updatedAt: '2026-09-30T10:00:00Z'
    },
    {
      id: 2, theme: 'sound', templateType: 'TIMELINE', title: '书院千年大事记',
      summaryText: null, sort: 1, updatedAt: '2026-09-30T09:00:00Z'
    }
  ],
  nextCursor: 'cur-1'
};

const PAGE2: PublicCardPage = {
  items: [
    {
      id: 3, theme: 'cuisine', templateType: 'COMPARE', title: '湘味两派对比',
      summaryText: '剁椒鱼头与口味虾的辣从何来。', sort: 2, updatedAt: '2026-09-30T08:00:00Z'
    }
  ],
  nextCursor: null
};

/** 直挂 CardsView 的独立 memory 路由 */
async function mountList(query: Record<string, string> = {}) {
  const local = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/cards', component: CardsView },
      { path: '/cards/:id', component: { render: () => null } }
    ]
  });
  await local.push({ path: '/cards', query });
  await local.isReady();
  const wrapper = mount(CardsView, { global: { plugins: [local] } });
  await flushPromises();
  return { wrapper, local };
}

describe('CardsView(/cards 列表页)', () => {
  it('首屏渲染两页数据中的第一页:标题/摘要截断行/模板 chip,尾部有加载更多', async () => {
    mockedList.mockResolvedValue(PAGE1);
    const { wrapper } = await mountList();
    const rows = wrapper.findAll('.cardrow');
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain('岳麓书院');
    expect(rows[0].text()).toContain('中国四大书院之一');
    expect(rows[0].text()).toContain('图文卡');
    expect(rows[1].text()).toContain('时间线卡');
    expect(wrapper.find('.more').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('没有更多了');
    expect(mockedList).toHaveBeenCalledWith({ theme: undefined, q: undefined, cursor: undefined });
  });

  it('summaryText 为 null 的行不渲染摘要,不报错', async () => {
    mockedList.mockResolvedValue(PAGE1);
    const { wrapper } = await mountList();
    expect(wrapper.findAll('.cardrow')[1].find('.row-sum').exists()).toBe(false);
  });

  it('点「加载更多」带 cursor 追加下一页,无 nextCursor 后显示「没有更多了」', async () => {
    mockedList.mockResolvedValueOnce(PAGE1).mockResolvedValueOnce(PAGE2);
    const { wrapper } = await mountList();
    await wrapper.find('.more').trigger('click');
    await flushPromises();
    expect(mockedList).toHaveBeenLastCalledWith({ theme: undefined, q: undefined, cursor: 'cur-1' });
    expect(wrapper.findAll('.cardrow')).toHaveLength(3);
    expect(wrapper.text()).toContain('没有更多了');
    expect(wrapper.find('.more').exists()).toBe(false);
  });

  it('搜索回车:路由带 q 并重新拉数据', async () => {
    mockedList.mockResolvedValue(PAGE1);
    const { wrapper, local } = await mountList();
    await wrapper.find('input[type="search"]').setValue('岳麓');
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(local.currentRoute.value.query.q).toBe('岳麓');
    expect(mockedList).toHaveBeenLastCalledWith({ theme: undefined, q: '岳麓', cursor: undefined });
  });

  it('专题 tab:点击带 theme,当前项高亮,数据按专题拉取', async () => {
    mockedList.mockResolvedValue(PAGE1);
    const { wrapper, local } = await mountList();
    const tabs = wrapper.findAll('.tab');
    expect(tabs.map((t) => t.text())).toEqual(['全部', '书院地标', '湘菜风物', '声音科学']);
    const cuisine = tabs.find((t) => t.text() === '湘菜风物');
    await cuisine?.trigger('click');
    await flushPromises();
    expect(local.currentRoute.value.query.theme).toBe('cuisine');
    expect(cuisine?.classes()).toContain('on');
    expect(mockedList).toHaveBeenLastCalledWith({ theme: 'cuisine', q: undefined, cursor: undefined });
  });

  it('首屏失败:错误文案 + 重试按钮,点击恢复渲染', async () => {
    mockedList.mockRejectedValueOnce(new Error('网络异常')).mockResolvedValueOnce(PAGE1);
    const { wrapper } = await mountList();
    expect(wrapper.text()).toContain('网络异常');
    const retry = wrapper.find('.retry');
    expect(retry.exists()).toBe(true);
    await retry.trigger('click');
    await flushPromises();
    expect(wrapper.findAll('.cardrow')).toHaveLength(2);
    expect(wrapper.find('.retry').exists()).toBe(false);
  });

  it('加载更多失败:保留已加载行,行内「加载失败,点击重试」可恢复', async () => {
    mockedList.mockResolvedValueOnce(PAGE1).mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(PAGE2);
    const { wrapper } = await mountList();
    await wrapper.find('.more').trigger('click');
    await flushPromises();
    // 已加载列表未被清空,出现行内重试
    expect(wrapper.findAll('.cardrow')).toHaveLength(2);
    const retry = wrapper.find('.more.err');
    expect(retry.text()).toContain('加载失败,点击重试');
    await retry.trigger('click');
    await flushPromises();
    expect(wrapper.findAll('.cardrow')).toHaveLength(3);
    expect(wrapper.text()).toContain('没有更多了');
  });

  it('时序守卫:tab 切换重置后,过期挂起的「加载更多」响应被丢弃', async () => {
    let releaseAppend: (page: PublicCardPage) => void = () => {};
    const deferredAppend = new Promise<PublicCardPage>((resolve) => {
      releaseAppend = resolve;
    });
    mockedList.mockResolvedValueOnce(PAGE1) // 首屏
      .mockReturnValueOnce(deferredAppend) // 点「加载更多」后挂起
      .mockResolvedValueOnce(PAGE1); // tab 切换触发重置
    const { wrapper } = await mountList();
    await wrapper.find('.more').trigger('click');
    const cuisine = wrapper.findAll('.tab').find((t) => t.text() === '湘菜风物');
    await cuisine?.trigger('click');
    await flushPromises();
    expect(wrapper.findAll('.cardrow')).toHaveLength(2); // 重置后的首页两行
    releaseAppend(PAGE2); // 迟到的 append 响应此刻才落地
    await flushPromises();
    // 过期响应被丢弃:不追加、不串页
    expect(wrapper.findAll('.cardrow')).toHaveLength(2);
    expect(wrapper.text()).not.toContain('湘味两派对比');
  });

  it('空结果:显示「换个关键词试试」空态', async () => {
    mockedList.mockResolvedValue({ items: [], nextCursor: null });
    const { wrapper } = await mountList({ q: '不存在词' });
    expect(wrapper.text()).toContain('没有找到相关卡片');
    expect(wrapper.text()).toContain('换个关键词试试');
  });

  it('从路由 query 回填搜索词与专题(首页跳转联通)', async () => {
    mockedList.mockResolvedValue(PAGE1);
    const { wrapper } = await mountList({ q: '岳麓书院', theme: 'academy' });
    const input = wrapper.find('input[type="search"]').element as HTMLInputElement;
    expect(input.value).toBe('岳麓书院');
    const active = wrapper.find('.tab.on');
    expect(active.text()).toBe('书院地标');
    expect(mockedList).toHaveBeenCalledWith({ theme: 'academy', q: '岳麓书院', cursor: undefined });
  });
});
