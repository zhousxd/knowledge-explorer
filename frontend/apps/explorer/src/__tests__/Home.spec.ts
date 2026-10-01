import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';
import App from '../App.vue';
import router from '../router';

describe('Home(经真实路由宿主)', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('有 token 时经路由渲染品牌标题与三专题', async () => {
    localStorage.setItem('ke_ex_token', 'smoke-token'); // 守卫要求登录态
    const pinia = createPinia();
    setActivePinia(pinia);
    await router.push('/home');
    await router.isReady();
    // 与 main.ts 相同的组合:RouterView 宿主 + 真实路由
    const wrapper = mount(App, { global: { plugins: [pinia, router] } });
    await flushPromises();
    expect(wrapper.find('h1').text()).toBe('知识探索');
    expect(wrapper.text()).toContain('书院地标');
    expect(wrapper.text()).toContain('湘菜风物');
    expect(wrapper.text()).toContain('声音科学');
  });

  it('无 token 访问受保护路由跳转登录页', async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    // 单例路由可能停留在 /home(上一用例残留):同路径 push 是重复导航不跑守卫,先去 /login 再导航
    await router.push('/login');
    await router.push('/home');
    expect(router.currentRoute.value.path).toBe('/login');
  });
});
