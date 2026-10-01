import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import App from '../App.vue';
import ResumeCard from '../components/ResumeCard.vue';
import router from '../router';
import { useAuthStore } from '../stores/auth';
import CardsView from '../views/CardsView.vue';
import HomeView from '../views/HomeView.vue';

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
      { path: '/login', component: { render: () => null } }
    ]
  });
  await local.push('/home');
  await local.isReady();
  const wrapper = mount(HomeView, { global: { plugins: [pinia, local] } });
  await flushPromises();
  return { wrapper, local };
}

describe('ResumeCard(继续探索卡)', () => {
  it('渲染眉标/宋体标题/进度摘要,点击按钮 emit continue', async () => {
    const wrapper = mount(ResumeCard, {
      props: { title: '岳麓书院：从选址到人物', progress: '昨天暂存', nodes: 5, branches: 1 }
    });
    expect(wrapper.text()).toContain('继续探索 · 昨天暂存');
    expect(wrapper.find('h2').text()).toBe('岳麓书院：从选址到人物');
    expect(wrapper.text()).toContain('已探索 5 个节点 · 1 个分支');
    await wrapper.find('button').trigger('click');
    expect(wrapper.emitted('continue')).toHaveLength(1);
  });
});

describe('HomeView(直挂,登录态分支)', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('未登录:顶部渲染登录引导细条,点击跳 /login', async () => {
    const { wrapper, local } = await mountHome();
    const hint = wrapper.find('.login-hint');
    expect(hint.exists()).toBe(true);
    expect(hint.text()).toBe('登录后记录你的探索路径');
    await hint.trigger('click');
    await flushPromises();
    expect(local.currentRoute.value.path).toBe('/login');
  });

  it('已登录:登录细条隐藏,继续探索卡因无会话数据整卡隐藏', async () => {
    const { wrapper } = await mountHome({ loggedIn: true });
    expect(wrapper.find('.login-hint').exists()).toBe(false);
    expect(wrapper.findComponent(ResumeCard).exists()).toBe(false);
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
    expect(wrapper.text()).toContain('cuisine'); // 占位页透传展示
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
    expect(wrapper.text()).toContain('岳麓书院'); // 占位页透传展示
  });

  it('无 token 访问受保护路由跳转登录页', async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    // beforeEach 已清 token,守卫应把 /home 拦回 /login
    await router.push('/login');
    await router.push('/home');
    expect(router.currentRoute.value.path).toBe('/login');
  });
});
