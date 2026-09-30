import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it } from 'vitest';
import App from '../App.vue';
import router from '../router';

describe('Workbench App', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('有 token 时经路由挂载工作台布局', async () => {
    localStorage.setItem('ke_wb_token', 'test-token');
    const pinia = createPinia();
    setActivePinia(pinia);
    router.push('/cards');
    await router.isReady();
    // 与 main.ts 相同的组合:Element Plus 由插件全局注册(卡片管理表格/抽屉依赖)
    const wrapper = mount(App, { global: { plugins: [pinia, ElementPlus, router] } });
    expect(wrapper.text()).toContain('卡片管理');
    expect(wrapper.text()).toContain('数据看板');
  });

  it('无 token 访问受保护路由跳转登录页', async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    await router.push('/metrics');
    expect(router.currentRoute.value.path).toBe('/login');
  });
});
