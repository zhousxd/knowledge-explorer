import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import App from '../App.vue';

describe('Workbench App', () => {
  it('渲染侧栏导航', () => {
    const wrapper = mount(App, { global: { stubs: { 'router-view': true } } });
    expect(wrapper.text()).toContain('卡片管理');
    expect(wrapper.text()).toContain('审核中心');
  });
});
