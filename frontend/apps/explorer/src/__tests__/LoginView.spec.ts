import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/http';
import LoginView from '../views/LoginView.vue';
import { useAuthStore } from '../stores/auth';

async function mountLogin() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginView },
      { path: '/', component: { template: '<div class="home-stub">探索首页</div>' } }
    ]
  });
  const pinia = createPinia();
  await router.push('/login');
  await router.isReady();
  const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });
  return { wrapper, router, store: useAuthStore(pinia) };
}

describe('LoginView', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('默认渲染验证码登录 tab,可切换到密码登录', async () => {
    const { wrapper } = await mountLogin();

    expect(wrapper.find('[role="tablist"]').text()).toContain('验证码登录');
    expect(wrapper.find('[role="tablist"]').text()).toContain('密码登录');
    // 默认 tab:验证码表单可见(取码按钮),密码输入不存在
    expect(wrapper.find('input[name="code"]').exists()).toBe(true);
    expect(wrapper.find('button.send-code').exists()).toBe(true);
    expect(wrapper.find('input[name="password"]').exists()).toBe(false);

    await wrapper.findAll('[role="tab"]')[1]!.trigger('click');
    expect(wrapper.find('input[name="password"]').exists()).toBe(true);
    expect(wrapper.find('input[name="code"]').exists()).toBe(false);

    // 切回验证码 tab
    await wrapper.findAll('[role="tab"]')[0]!.trigger('click');
    expect(wrapper.find('input[name="code"]').exists()).toBe(true);
  });

  it('获取验证码后进入 60s 倒计时(禁用+秒数),走完自动恢复', async () => {
    vi.useFakeTimers();
    const { wrapper, store } = await mountLogin();
    vi.spyOn(store, 'sendCode').mockResolvedValue(undefined);

    const phoneInput = wrapper.find('input[name="phone"]');
    await phoneInput.setValue('13900000001');
    const btn = wrapper.find('button.send-code');
    expect(btn.text()).toBe('获取验证码');

    await btn.trigger('click');
    await flushPromises();
    expect(wrapper.find('button.send-code').text()).toContain('60');
    expect(wrapper.find('button.send-code').attributes('disabled')).toBeDefined();

    vi.advanceTimersByTime(1000);
    await flushPromises();
    expect(wrapper.find('button.send-code').text()).toContain('59');

    // 走完 60s:恢复可点
    vi.advanceTimersByTime(60_000);
    await flushPromises();
    expect(wrapper.find('button.send-code').text()).toBe('获取验证码');
    expect(wrapper.find('button.send-code').attributes('disabled')).toBeUndefined();
  });

  it('手机号不合法时禁用获取验证码', async () => {
    const { wrapper } = await mountLogin();
    await wrapper.find('input[name="phone"]').setValue('abc');
    expect(wrapper.find('button.send-code').attributes('disabled')).toBeDefined();
  });

  it('验证码登录提交调 store.loginByCode 并跳 /', async () => {
    const { wrapper, router, store } = await mountLogin();
    const spy = vi.spyOn(store, 'loginByCode').mockResolvedValue(undefined);

    await wrapper.find('input[name="phone"]').setValue('13900000001');
    await wrapper.find('input[name="code"]').setValue('246810');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(spy).toHaveBeenCalledWith('13900000001', '246810');
    expect(router.currentRoute.value.path).toBe('/');
  });

  it('密码登录提交调 store.login 并跳 /', async () => {
    const { wrapper, router, store } = await mountLogin();
    const loginSpy = vi.spyOn(store, 'login').mockResolvedValue(undefined);
    const codeSpy = vi.spyOn(store, 'loginByCode').mockResolvedValue(undefined);

    await wrapper.findAll('[role="tab"]')[1]!.trigger('click');
    await wrapper.find('input[name="phone"]').setValue('13800000000');
    await wrapper.find('input[name="password"]').setValue('pw-123456');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(loginSpy).toHaveBeenCalledWith('13800000000', 'pw-123456');
    expect(codeSpy).not.toHaveBeenCalled();
    expect(router.currentRoute.value.path).toBe('/');
  });

  it('登录失败展示后端错误文案', async () => {
    const { wrapper, router, store } = await mountLogin();
    vi.spyOn(store, 'loginByCode').mockRejectedValue(new ApiError(401, '验证码错误'));

    await wrapper.find('input[name="phone"]').setValue('13900000001');
    await wrapper.find('input[name="code"]').setValue('000000');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(wrapper.find('[role="alert"]').text()).toBe('验证码错误');
    expect(router.currentRoute.value.path).toBe('/login');
  });
});
