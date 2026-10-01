import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
// router 须先于 LoginView 引入:LoginView → stores/auth → router 成环,若让 LoginView
// 先求值,router 模块会在拿到未初始化的 LoginView 组件时建表,/login 记录会从匹配表丢失
import router from '../router';
import LoginView from '../views/LoginView.vue';
import { useAuthStore } from '../stores/auth';

/**
 * 登录 redirect 贯穿(真实路由):
 * - 守卫:无 token 访问受保护路由 → /login?redirect=<完整路径>;有 token 放行;
 * - LoginView:登录成功 push redirect(校验站内路径,防外链);
 * - 端到端:/login?redirect=/cards/9 登录成功 → 回 /cards/9。
 */
describe('登录 redirect 贯穿(真实路由)', () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
  });

  it('守卫:无 token 访问受保护路由 → /login?redirect=完整路径(query 含原有查询)', async () => {
    // 现网路由当前全部匿名可浏览;临时挂一条未标 public 的受保护路由验证守卫行为
    router.addRoute({ path: '/path-demo', component: { render: () => null }, meta: { title: 't' } });
    await router.push('/path-demo?a=1');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/login');
    expect(router.currentRoute.value.query.redirect).toBe('/path-demo?a=1');
  });

  it('守卫:已登录(token 在 localStorage)访问受保护路由直接放行', async () => {
    router.addRoute({ path: '/path-demo', component: { render: () => null }, meta: { title: 't' } });
    localStorage.setItem('ke_ex_token', 'tk');
    await router.push('/path-demo?a=1');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/path-demo');
  });

  it('端到端:/login?redirect=/cards/9 登录成功 → 回到 /cards/9(真实路由表)', async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const spy = vi.spyOn(useAuthStore(pinia), 'loginByCode').mockResolvedValue(undefined);
    await router.push('/login?redirect=/cards/9');
    await router.isReady();
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });

    await wrapper.find('input[name="phone"]').setValue('13900000001');
    await wrapper.find('input[name="code"]').setValue('246810');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(spy).toHaveBeenCalledWith('13900000001', '246810');
    expect(router.currentRoute.value.path).toBe('/cards/9');
  });

  it('redirect 非站内路径(https 外链)→ 登录后回落首页,防外链跳转', async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    vi.spyOn(useAuthStore(pinia), 'loginByCode').mockResolvedValue(undefined);
    await router.push('/login?redirect=https://evil.example');
    await router.isReady();
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });

    await wrapper.find('input[name="phone"]').setValue('13900000001');
    await wrapper.find('input[name="code"]').setValue('246810');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(router.currentRoute.value.path).toBe('/home');
  });
});
