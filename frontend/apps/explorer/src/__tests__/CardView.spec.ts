import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { showToast } from 'vant';
import { getCard, fetchCardEntries } from '../api/cards';
import type { CardDetail, CardEntryGroup } from '../api/cards';
import { favorite, unfavorite } from '../api/favorites';
import { ApiError } from '../api/http';
import { addNode, createSession } from '../api/sessions';
import type { PathNode } from '../api/sessions';
import { submitRun } from '../api/runs';
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
vi.mock('../api/sessions', () => ({
  createSession: vi.fn(),
  addNode: vi.fn(),
  fetchLatestSession: vi.fn(),
  fetchMySessions: vi.fn(),
  fetchSessionTree: vi.fn(),
  updateExplainLevel: vi.fn()
}));
vi.mock('../api/runs', () => ({ submitRun: vi.fn(), fetchRun: vi.fn(), isTerminal: vi.fn() }));
vi.mock('vant', () => ({ showToast: vi.fn() }));
const mockedGet = vi.mocked(getCard);
const mockedEntries = vi.mocked(fetchCardEntries);
const mockedFavorite = vi.mocked(favorite);
const mockedUnfavorite = vi.mocked(unfavorite);
const mockedCreateSession = vi.mocked(createSession);
const mockedAddNode = vi.mocked(addNode);
const mockedSubmitRun = vi.mocked(submitRun);

/** addNode 响应样本(P5-21 服务键挂根新节点) */
const NEW_NODE: PathNode = {
  nodeId: 6, parentNodeId: null, cardVersionId: null, entryId: null,
  questionText: '讲清楚:岳麓书院', isNewKnowledge: false,
  visitedAt: '2026-09-30T10:00:00Z', cardTitle: null
};

const TEXT_CARD: CardDetail = {
  id: 1,
  theme: 'academy',
  templateType: 'TEXT',
  title: '岳麓书院',
  cardVersionId: 11,
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

/** 卡 9 的入口组(与卡 1 区分:仅 1 条默认入口,校验 reqSeq 守卫不串卡) */
const ENTRIES_OF_9: CardEntryGroup = {
  cardId: 9,
  defaultEntries: [{ ...ENTRIES.defaultEntries[0]!, id: 99, name: 'B 卡独有入口' }],
  folded: []
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
      { path: '/login', component: { render: () => null } },
      { path: '/runs/:id(\\d+)', component: { render: () => null } }
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

  // —— 配图(二期图片功能):有 image 渲染真图,无 image 回落示意图占位 ——

  it('TEXT 卡带配图 → figure.kimg-photo 渲染 img(alt 回落卡题)', async () => {
    mockedGet.mockResolvedValue({
      ...TEXT_CARD,
      content: {
        summary: '中国四大书院之一。',
        sections: [{ h: '书院的由来', body: '北宋开宝九年创办。', citations: [1] }],
        related: [{ cardId: 9, relation: '相关联', why: '朱张会讲的人物细节' }],
        image: { id: 9, url: '/api/images/9', alt: '岳麓书院讲堂' }
      }
    } as CardDetail);
    const { wrapper } = await mountCard('1');
    const photo = wrapper.find('.kimg-photo img');
    expect(photo.exists()).toBe(true);
    expect(photo.attributes('src')).toBe('/api/images/9');
    expect(photo.attributes('alt')).toBe('岳麓书院讲堂');
    expect(wrapper.find('.kimg-tag').exists()).toBe(false); // 占位与真图互斥
  });

  it('TEXT 卡无配图 → 回落示意图占位(temple 图标+角标)', async () => {
    const { wrapper } = await mountCard('1');
    expect(wrapper.find('.kimg-photo').exists()).toBe(false);
    expect(wrapper.find('.kimg .kimg-ic').exists()).toBe(true);
    expect(wrapper.find('.kimg-tag').text()).toBe('示意图');
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

  it('时序守卫:卡 A 详情慢响应后到 → 丢弃,仍显示卡 B 内容', async () => {
    let resolveSlow!: (v: CardDetail) => void;
    mockedGet.mockImplementation((id: number) => {
      if (id === 1) {
        return new Promise<CardDetail>((resolve) => { resolveSlow = resolve; });
      }
      return Promise.resolve({ ...TEXT_CARD, id: 9, title: '朱张会讲' });
    });
    const { wrapper, local } = await mountCard('1');
    // 卡 A 挂起:页面停在加载态
    expect(wrapper.text()).toContain('加载中');
    // 直接切到卡 B(入口慢挂起,TextCard 未渲染,无法 emit open)
    void local.push('/cards/9');
    await flushPromises();
    expect(wrapper.find('h1').text()).toBe('朱张会讲');
    // 卡 A 慢响应此刻才到:必须被丢弃,不得覆盖卡 B
    resolveSlow({ ...TEXT_CARD, id: 1 });
    await flushPromises();
    expect(local.currentRoute.value.path).toBe('/cards/9');
    expect(wrapper.find('h1').text()).toBe('朱张会讲');
  });

  it('时序守卫:卡 A 入口慢响应后到 → 丢弃,不覆盖卡 B 入口', async () => {
    let resolveSlowEntries!: (v: CardEntryGroup) => void;
    mockedGet.mockResolvedValue(TEXT_CARD);
    mockedEntries.mockImplementation((id: number) => {
      if (id === 1) {
        return new Promise<CardEntryGroup>((resolve) => { resolveSlowEntries = resolve; });
      }
      return Promise.resolve(ENTRIES_OF_9);
    });
    const { wrapper, local } = await mountCard('1', { authed: true });
    // 卡 A 入口挂起:入口列表为空但主内容正常
    expect(wrapper.findAll('.entry')).toHaveLength(0);
    expect(wrapper.find('h1').text()).toBe('岳麓书院');
    void local.push('/cards/9');
    await flushPromises();
    expect(wrapper.findAll('.entry')).toHaveLength(1);
    expect(wrapper.text()).toContain('B 卡独有入口');
    // 卡 A 入口慢响应此刻才到:必须被丢弃
    resolveSlowEntries(ENTRIES);
    await flushPromises();
    expect(wrapper.findAll('.entry')).toHaveLength(1);
    expect(wrapper.text()).toContain('B 卡独有入口');
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

  it('匿名(无 token):不调入口接口,给出登录引导;点引导跳 /login 并带 redirect', async () => {
    const { wrapper, local } = await mountCard();
    expect(mockedEntries).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('登录后查看这张卡的探索入口');
    await wrapper.find('.sec .fold').trigger('click');
    await flushPromises();
    expect(local.currentRoute.value.path).toBe('/login');
    expect(local.currentRoute.value.query.redirect).toBe('/cards/1');
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

  it('收藏按钮:匿名点击 → 跳 /login?redirect=/cards/1,不调 API', async () => {
    const { wrapper, local } = await mountCard();
    await wrapper.find('.fav-btn').trigger('click');
    await flushPromises();
    expect(local.currentRoute.value.path).toBe('/login');
    expect(local.currentRoute.value.query.redirect).toBe('/cards/1');
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

// —— 服务栏接线(P5-21):三键 → 建会话/复用 → 挂节点 → 提交 run → 跳执行态页 ——

describe('CardView 服务栏接线(P5-21)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    mockedGet.mockResolvedValue(TEXT_CARD);
    mockedEntries.mockResolvedValue(ENTRIES);
    mockedCreateSession.mockResolvedValue({ sessionId: 3 });
    mockedAddNode.mockResolvedValue(NEW_NODE);
    mockedSubmitRun.mockResolvedValue({ runId: 42 });
    Element.prototype.scrollIntoView = vi.fn();
  });

  it('点「讲清楚」:ensureForCard 建会话 → addNode(挂根)→ submitRun → 跳 /runs/42(state 带 question)', async () => {
    const { wrapper, local } = await mountCard('1', { authed: true });
    await wrapper.findAll('.svc')[0]!.trigger('click');
    await flushPromises();

    expect(mockedCreateSession).toHaveBeenCalledTimes(1);
    expect(mockedCreateSession).toHaveBeenCalledWith('academy', null);
    expect(mockedAddNode).toHaveBeenCalledWith(3, {
      cardVersionId: 11, questionText: '讲清楚:岳麓书院'
    });
    expect(mockedSubmitRun).toHaveBeenCalledWith({
      cardVersionId: 11, sessionId: 3, nodeId: 6,
      question: '讲清楚:岳麓书院', level: 'SIMPLE'
    });
    expect(local.currentRoute.value.path).toBe('/runs/42');
    // 路由 state 带上提交上下文(RunView 重试/问题展示依赖)
    expect((local.options.history.state as Record<string, unknown>).keRun).toBeTruthy();
  });

  it('另两键:比较走 COMPARE 通道(serviceType,Task 23),整理仍讲解;question 按冻结文案合成', async () => {
    const { wrapper } = await mountCard('1', { authed: true });
    await wrapper.findAll('.svc')[1]!.trigger('click');
    await flushPromises();
    expect(mockedSubmitRun.mock.calls[0]?.[0].question).toBe('比较:岳麓书院');
    expect(mockedSubmitRun.mock.calls[0]?.[0].serviceType).toBe('COMPARE');

    await wrapper.findAll('.svc')[2]!.trigger('click');
    await flushPromises();
    expect(mockedSubmitRun.mock.calls[1]?.[0].question).toBe('整理关于 岳麓书院 的发现');
    // 整理不传 serviceType → 后端默认 EXPLAIN(讲解通道)
    expect(mockedSubmitRun.mock.calls[1]?.[0].serviceType).toBeUndefined();
  });

  it('二次点击复用已建会话,不再 createSession', async () => {
    const { wrapper } = await mountCard('1', { authed: true });
    await wrapper.findAll('.svc')[0]!.trigger('click');
    await flushPromises();
    await wrapper.findAll('.svc')[0]!.trigger('click');
    await flushPromises();
    expect(mockedCreateSession).toHaveBeenCalledTimes(1);
    expect(mockedAddNode).toHaveBeenCalledTimes(2);
    expect(mockedSubmitRun).toHaveBeenCalledTimes(2);
  });

  it('提交中三键置 busy 防双击,完成后恢复', async () => {
    let resolveNode!: (v: PathNode) => void;
    mockedAddNode.mockImplementation(
      () => new Promise<PathNode>((resolve) => { resolveNode = resolve; }));
    const { wrapper } = await mountCard('1', { authed: true });
    await wrapper.findAll('.svc')[0]!.trigger('click');
    await flushPromises(); // createSession 已过,addNode 挂起
    expect(wrapper.find('.svc').attributes('disabled')).toBeDefined();
    // 挂起期间再点不重复提交
    await wrapper.findAll('.svc')[0]!.trigger('click');
    await flushPromises();
    expect(mockedAddNode).toHaveBeenCalledTimes(1);

    resolveNode(NEW_NODE);
    await flushPromises();
    expect(wrapper.find('.svc').attributes('disabled')).toBeUndefined();
    expect(mockedSubmitRun).toHaveBeenCalledTimes(1);
  });

  it('429 配额超限:toast 显示 envelope 文案,留在卡页不跳转', async () => {
    mockedSubmitRun.mockRejectedValue(new ApiError(429, '今日 30 次智能服务已用完,明早 8 点恢复'));
    const { wrapper, local } = await mountCard('1', { authed: true });
    await wrapper.findAll('.svc')[0]!.trigger('click');
    await flushPromises();
    expect(showToast).toHaveBeenCalledWith('今日 30 次智能服务已用完,明早 8 点恢复');
    expect(local.currentRoute.value.path).toBe('/cards/1');
    // 按钮复位可再点(不卡死在 busy)
    expect(wrapper.find('.svc').attributes('disabled')).toBeUndefined();
  });

  it('匿名点服务键:引导登录(带 redirect),不建会话不提交', async () => {
    const { wrapper, local } = await mountCard();
    await wrapper.findAll('.svc')[0]!.trigger('click');
    await flushPromises();
    expect(mockedCreateSession).not.toHaveBeenCalled();
    expect(mockedSubmitRun).not.toHaveBeenCalled();
    expect(local.currentRoute.value.path).toBe('/login');
    expect(local.currentRoute.value.query.redirect).toBe('/cards/1');
  });
});

// —— 探索入口接线(A1③ 数据面,review P5-FIX):三类入口都先挂节点(entryId 溯源) ——

describe('CardView 探索入口接线(A1③ 数据面)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    mockedGet.mockResolvedValue(TEXT_CARD);
    mockedEntries.mockResolvedValue(ENTRIES);
    mockedCreateSession.mockResolvedValue({ sessionId: 3 });
    mockedAddNode.mockResolvedValue(NEW_NODE);
    mockedSubmitRun.mockResolvedValue({ runId: 42 });
    Element.prototype.scrollIntoView = vi.fn();
  });

  it('链接入口:addNode(当前卡版本+entryId)后跳目标卡;挂节点失败 toast 不阻断跳转', async () => {
    const { wrapper, local } = await mountCard('1', { authed: true });
    // defaultEntries[1] = LINK_CARD「哪些人物与这里有关」→ 卡 9
    await wrapper.findAll('.entry')[1]!.trigger('click');
    await flushPromises();
    expect(mockedAddNode).toHaveBeenCalledWith(3, {
      cardVersionId: 11, entryId: 2, questionText: '哪些人物与这里有关'
    });
    expect(local.currentRoute.value.path).toBe('/cards/9');
    expect(mockedSubmitRun).not.toHaveBeenCalled();

    // 挂节点失败:toast 提示但导航照常(节点是路径侧记录,不阻断主行为)
    mockedAddNode.mockRejectedValueOnce(new ApiError(500, '挂节点失败'));
    await wrapper.findAll('.entry')[1]!.trigger('click');
    await flushPromises();
    expect(showToast).toHaveBeenCalledWith('挂节点失败');
    expect(local.currentRoute.value.path).toBe('/cards/9');
  });

  it('智能体服务入口:addNode(entryId)后以入口名为 question 发起讲解 run', async () => {
    const { wrapper, local } = await mountCard('1', { authed: true });
    // defaultEntries[0] = AGENT_SERVICE「为什么建在这里」
    await wrapper.findAll('.entry')[0]!.trigger('click');
    await flushPromises();
    expect(mockedAddNode).toHaveBeenCalledWith(3, {
      cardVersionId: 11, entryId: 1, questionText: '为什么建在这里'
    });
    expect(mockedSubmitRun).toHaveBeenCalledWith({
      cardVersionId: 11, sessionId: 3, nodeId: 6,
      question: '为什么建在这里', level: 'SIMPLE'
    });
    expect(local.currentRoute.value.path).toBe('/runs/42');
    // 路由 state 带提交上下文(结果页刷新回读依赖)
    expect((local.options.history.state as Record<string, unknown>).keRun).toBeTruthy();
  });

  it('比较入口:addNode(entryId)后以入口名为 question 发起 COMPARE run', async () => {
    mockedEntries.mockResolvedValue({
      cardId: 1,
      defaultEntries: [
        {
          id: 7, name: '书院与藏书楼', type: 'COMPARE', relationLabel: null,
          targetCardId: null, serviceType: null, scope: 'PUBLIC', status: 'ACTIVE', mine: false
        }
      ],
      folded: []
    });
    const { wrapper, local } = await mountCard('1', { authed: true });
    await wrapper.find('.entry').trigger('click');
    await flushPromises();
    expect(mockedAddNode).toHaveBeenCalledWith(3, {
      cardVersionId: 11, entryId: 7, questionText: '书院与藏书楼'
    });
    expect(mockedSubmitRun).toHaveBeenCalledWith({
      cardVersionId: 11, sessionId: 3, nodeId: 6,
      question: '书院与藏书楼', level: 'SIMPLE', serviceType: 'COMPARE'
    });
    expect(local.currentRoute.value.path).toBe('/runs/42');
  });
});
