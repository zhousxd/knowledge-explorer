import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import Home from '../pages/Home.vue';

describe('Home', () => {
  it('渲染品牌标题与三专题', () => {
    const wrapper = mount(Home);
    expect(wrapper.find('h1').text()).toBe('知识探索');
    expect(wrapper.text()).toContain('书院地标');
    expect(wrapper.text()).toContain('湘菜风物');
    expect(wrapper.text()).toContain('声音科学');
  });
});
