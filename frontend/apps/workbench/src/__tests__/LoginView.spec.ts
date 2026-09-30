import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import LoginView from '../views/LoginView.vue';
import { ApiError } from '../api/http';

const { loginMock } = vi.hoisted(() => ({ loginMock: vi.fn() }));
vi.mock('../stores/auth', () => ({ useAuthStore: () => ({ login: loginMock }) }));

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginView },
      { path: '/cards', component: { template: '<div />' } }
    ]
  });
  router.push('/login');
  await router.isReady();
  const wrapper = mount(LoginView, { global: { plugins: [router] } });
  return { wrapper, router };
}

describe('LoginView', () => {
  beforeEach(() => {
    loginMock.mockReset();
  });

  it('渲染手机号/密码输入与登录按钮', async () => {
    const { wrapper } = await mountView();
    const inputs = wrapper.findAll('input');
    expect(inputs).toHaveLength(2);
    expect(inputs[0]?.attributes('type')).toBe('tel');
    expect(inputs[1]?.attributes('type')).toBe('password');
    expect(wrapper.find('button[type="submit"]').text()).toBe('登录');
  });

  it('提交调用 store.login 并跳转 /cards', async () => {
    loginMock.mockResolvedValueOnce(undefined);
    const { wrapper, router } = await mountView();
    const [phone, password] = wrapper.findAll('input');
    await phone.setValue('13800000000');
    await password.setValue('secret');
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(loginMock).toHaveBeenCalledWith('13800000000', 'secret');
    expect(router.currentRoute.value.path).toBe('/cards');
  });

  it('登录失败展示错误消息且不跳转', async () => {
    loginMock.mockRejectedValueOnce(new ApiError(401, '账号或密码错误', 't1'));
    const { wrapper, router } = await mountView();
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(wrapper.find('.error').text()).toContain('账号或密码错误');
    expect(router.currentRoute.value.path).toBe('/login');
  });
});
