import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import App from '../App.vue';
import { fetchLatestSession } from '../api/sessions';
import type { ResumeSession } from '../api/sessions';
import ResumeCard from '../components/ResumeCard.vue';
import router from '../router';
import { useAuthStore } from '../stores/auth';
import CardsView from '../views/CardsView.vue';
import HomeView from '../views/HomeView.vue';

// /cards 已是接真数据的列表页(Task 14):App 宿主测试里 mock 掉列表接口,只验证导航联通;
// 会话端点 P4-16 已交付,HomeView 挂载时会拉 latest(P4-17 断点续探接线)——一并 mock
vi.mock('../api/cards', () => ({
  listPublicCards: vi.fn(async () => ({ items: [], nextCursor: null }))
}));
vi.mock('../api/sessions', () => ({
  fetchLatestSession: vi.fn(),
  fetchSessionTree: vi.fn(),
  addNode: vi.fn(),
  updateExplainLevel: vi.fn(),
  fetchMySessions: vi.fn(),
  createSession: vi.fn()
}));
const mockedLatest = vi.mocked(fetchLatestSession);

/** P4-16 冻结 ResumeSession 形状样本(时间取动态「昨天」,防跨机时区漂移) */
function resumeFixture(): ResumeSession {
  return {
    sessionId: 7,
    theme: 'academy',
    title: '岳麓书院：从选址到人物',
    lastVisitedAt: new Date(Date.now() - 86400000).toISOString(),
    nodeCount: 5,
    branchCount: 1,
    openQuestionCount: 0
  };
}

/** 直挂 HomeView 用的独立 memory 路由:绕开全局守卫,便于覆盖登录态分支 */
async function mountHome(options: { loggedIn?: boolean } = {}) {
  const pinia = createPinia();
  setActivePinia(pinia);
  if (options.loggedIn) useAuthStore().token = 'it-token';
  const local = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/home', component: HomeView },
      { path: '/cards', component: CardsView },
      { path: '/path', component: { render: () => null } },
      { path: '/login', component: { render: () => null } },
      { path: '/me', component: { render: () => null } }
    ]
  });
  await local.push('/home');
  await local.isReady();
  const wrapper = mount(HomeView, { global: { plugins: [pinia, local] } });
  await flushPromises();
  return { wrapper, local };
}

describe('ResumeCard(继续探索卡,P4-17 新形状)', () => {
  it('渲染眉标(相对时间+探索)/宋体标题/进度摘要,点击按钮 emit continue', async () => {
    const wrapper = mount(ResumeCard, { props: { session: resumeFixture() } });
    expect(wrapper.text()).toContain('继续探索 · 昨天探索');
    expect(wrapper.find('h2').text()).toBe('岳麓书院：从选址到人物');
    expect(wrapper.text()).toContain('5 个节点 · 1 个分支');
    await wrapper.find('button').trigger('click');
    expect(wrapper.emitted('continue')).toHaveLength(1);
  });
});

describe('HomeView(直挂,登录态分支)', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    mockedLatest.mockResolvedValue(null);
  });

  it('未登录:登录引导细条渲染且不拉会话,点击跳 /login', async () => {
    const { wrapper, local } = await mountHome();
    expect(mockedLatest).not.toHaveBeenCalled();
    const hint = wrapper.find('.login-hint');
    expect(hint.exists()).toBe(true);
    expect(hint.text()).toBe('登录后记录你的探索路径');
    await hint.trigger('click');
    await flushPromises();
    expect(local.currentRoute.value.path).toBe('/login');
  });

  it('已登录+有会话:ResumeCard 渲染新形状,continue 跳 /path?sessionId=;标题下有「我的路径」入口', async () => {
    mockedLatest.mockResolvedValue(resumeFixture());
    const { wrapper, local } = await mountHome({ loggedIn: true });

    expect(mockedLatest).toHaveBeenCalledTimes(1);
    const card = wrapper.findComponent(ResumeCard);
    expect(card.exists()).toBe(true);
    expect(card.props('session').sessionId).toBe(7);
    expect(wrapper.text()).toContain('5 个节点 · 1 个分支');
    // 「我的路径」入口(登录后常驻,替代原型的底导)
    const myPath = wrapper.find('.mypath');
    expect(myPath.exists()).toBe(true);
    await myPath.trigger('click');
    await flushPromises();
    expect(local.currentRoute.value.path).toBe('/path');

    // continue:带 sessionId query 进路径页定位恢复点
    await card.find('button').trigger('click');
    await flushPromises();
    expect(local.currentRoute.value.path).toBe('/path');
    expect(local.currentRoute.value.query.sessionId).toBe('7');
  });

  it('已登录+无会话(404→null):继续探索卡隐藏,「我的路径」入口仍在', async () => {
    const { wrapper } = await mountHome({ loggedIn: true });
    expect(wrapper.findComponent(ResumeCard).exists()).toBe(false);
    expect(wrapper.find('.mypath').exists()).toBe(true);
  });

  it('标题行右侧「我的」图标按钮(Task 34):登录/匿名都渲染,点击跳 /me', async () => {
    const { wrapper, local } = await mountHome({ loggedIn: true });
    const meBtn = wrapper.find('[data-testid="me-entry"]');
    expect(meBtn.exists()).toBe(true);
    expect(meBtn.attributes('aria-label')).toBe('个人空间');
    await meBtn.trigger('click');
    await flushPromises();
    expect(local.currentRoute.value.path).toBe('/me');

    // 匿名同样渲染(真实路由由 /me 守卫带 redirect 进登录)
    const anon = await mountHome();
    expect(anon.wrapper.find('[data-testid="me-entry"]').exists()).toBe(true);
  });
});

describe('Home(经真实路由宿主)', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  /**
   * 挂 RouterView 宿主经真实路由进 /home(与 main.ts 相同组合)。
   * 单例路由可能停留在上一用例路径:同路径 push 是重复导航不跑守卫,先去 /login 再导航。
   */
  async function gotoHome() {
    localStorage.setItem('ke_ex_token', 'smoke-token'); // 守卫要求登录态
    const pinia = createPinia();
    setActivePinia(pinia);
    await router.push('/login');
    await router.push('/home');
    const wrapper = mount(App, { global: { plugins: [pinia, router] } });
    await flushPromises();
    return wrapper;
  }

  it('渲染品牌标题/搜索框/三专题/演示推荐入口', async () => {
    const wrapper = await gotoHome();
    expect(wrapper.find('h1').text()).toBe('知识探索');
    expect(wrapper.find('input[type="search"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('书院地标');
    expect(wrapper.text()).toContain('湘菜风物');
    expect(wrapper.text()).toContain('声音科学');
    expect(wrapper.text()).toContain('12 张卡');
    expect(wrapper.text()).toContain('为什么建在这里');
    expect(wrapper.text()).toContain('剁椒鱼头');
    expect(wrapper.findAll('.demo-chip')).toHaveLength(2);
  });

  it('点击专题跳 /cards 并透传 theme query', async () => {
    const wrapper = await gotoHome();
    const themes = wrapper.findAll('.theme');
    expect(themes).toHaveLength(3);
    const dish = themes.find((theme) => theme.text().includes('湘菜风物'));
    expect(dish).toBeDefined();
    await dish?.trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/cards');
    expect(router.currentRoute.value.query.theme).toBe('cuisine');
    // 列表页专题 tab 按 query 高亮(联通验证)
    const active = wrapper.find('.tab.on');
    expect(active.exists()).toBe(true);
    expect(active.text()).toBe('湘菜风物');
  });

  it('搜索回车跳 /cards 并透传 q;空关键词不跳转', async () => {
    const wrapper = await gotoHome();
    const form = wrapper.find('form');
    await form.trigger('submit');
    expect(router.currentRoute.value.path).toBe('/home');
    await wrapper.find('input[type="search"]').setValue('岳麓书院');
    await form.trigger('submit');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/cards');
    expect(router.currentRoute.value.query.q).toBe('岳麓书院');
    // 列表页搜索框回填 q(联通验证)
    const cardsInput = wrapper.find('input[type="search"]').element as HTMLInputElement;
    expect(cardsInput.value).toBe('岳麓书院');
  });

  it('未登录访问公开页 /home 放行,并渲染登录引导细条', async () => {
    // beforeEach 已清 token;/home 标记 meta.public,守卫应放行
    const pinia = createPinia();
    setActivePinia(pinia);
    await router.push('/login');
    await router.push('/home');
    const wrapper = mount(App, { global: { plugins: [pinia, router] } });
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/home');
    expect(wrapper.find('.login-hint').text()).toBe('登录后记录你的探索路径');
  });

  it('无 token 访问受保护路由(未标 public)跳转登录页', async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    // 全部现存路由均已公开:临时挂一条未标 public 的路由,模拟 Phase 4 的 /path 等个性化页
    const removeRoute = router.addRoute({
      path: '/tmp-protected',
      component: { render: () => null },
      meta: { title: '受保护' }
    });
    try {
      await router.push('/login');
      await router.push('/tmp-protected');
      expect(router.currentRoute.value.path).toBe('/login');
    } finally {
      removeRoute();
    }
  });
});
