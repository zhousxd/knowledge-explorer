import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { describe, expect, it } from 'vitest';
import { createMemoryHistory, createRouter, type RouterOptions } from 'vue-router';
import WbLayout from '../layout/WbLayout.vue';
import { useAuthStore } from '../stores/auth';
import type { MeResp } from '../api/types';

const PLACEHOLDER = { template: '<div />' };

// 与真实 App 相同的挂载方式:宿主渲染 RouterView,由路由决定渲染 WbLayout(避免直接挂载 WbLayout 时 RouterView 递归渲染自身)
const HostApp = { template: '<RouterView />' };

function makeRouter() {
  const routes: RouterOptions['routes'] = [
    {
      path: '/',
      component: WbLayout,
      redirect: '/cards',
      children: [
        { path: 'cards', meta: { title: '卡片管理' }, component: PLACEHOLDER },
        { path: 'entries', meta: { title: '入口编排' }, component: PLACEHOLDER },
        { path: 'reviews', meta: { title: '审核中心' }, component: PLACEHOLDER },
        { path: 'assets', meta: { title: '知识资源' }, component: PLACEHOLDER },
        { path: 'metrics', meta: { title: '数据看板' }, component: PLACEHOLDER }
      ]
    }
  ];
  return createRouter({ history: createMemoryHistory(), routes });
}

async function mountLayout(path: string, user: MeResp | null) {
  const router = makeRouter();
  router.push(path);
  await router.isReady();
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore();
  auth.user = user;
  const wrapper = mount(HostApp, { global: { plugins: [router, pinia] } });
  return wrapper;
}

const EDITOR: MeResp = { id: 1, nickname: '阿编', role: 'EDITOR' };

describe('WbLayout', () => {
  it('渲染 5 个菜单项及图标', async () => {
    const wrapper = await mountLayout('/cards', EDITOR);
    const items = wrapper.findAll('.nav-item');
    expect(items.map((w) => w.text().trim())).toEqual(['卡片管理', '入口编排', '审核中心', '知识资源', '数据看板']);
    expect(items.map((w) => w.attributes('href'))).toEqual(['/cards', '/entries', '/reviews', '/assets', '/metrics']);
    expect(items.map((w) => w.find('use').attributes('href')))
      .toEqual(['#i-layers', '#i-plus', '#i-check', '#i-book', '#i-scale']);
  });

  it('当前页标题与面包屑「工作台 / X」', async () => {
    const wrapper = await mountLayout('/reviews', EDITOR);
    expect(wrapper.find('.crumb').text()).toContain('工作台');
    expect(wrapper.find('.crumb-cur').text()).toBe('审核中心');
  });

  it('EDITOR 显示「编辑」角色徽标与登出按钮', async () => {
    const wrapper = await mountLayout('/cards', EDITOR);
    expect(wrapper.find('.role-chip').text()).toBe('编辑');
    expect(wrapper.find('.logout').exists()).toBe(true);
  });

  it('当前路由菜单项带选中态,审核中心预留徽标位', async () => {
    const wrapper = await mountLayout('/entries', EDITOR);
    const links = wrapper.findAll('.nav-item');
    expect(links[1]?.classes()).toContain('router-link-active');
    expect(links[0]?.classes()).not.toContain('router-link-active');
    expect(links[2]?.find('.badge').exists()).toBe(true);
    expect(links[0]?.find('.badge').exists()).toBe(false);
  });

  it('未登录时不显示角色徽标', async () => {
    const wrapper = await mountLayout('/cards', null);
    expect(wrapper.find('.role-chip').exists()).toBe(false);
  });
});
