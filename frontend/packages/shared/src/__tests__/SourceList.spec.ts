import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import SourceList from '../components/SourceList.vue';

describe('SourceList', () => {
  it('渲染出处行与证据缺口警示行', () => {
    const wrapper = mount(SourceList, {
      props: {
        sources: [{ index: 1, title: '岳麓书院史略', locator: '第一章 p12–14', license: '已授权' }],
        gap: '原始文献记载有限，结论主要为现代学者归纳'
      }
    });
    expect(wrapper.text()).toContain('[1] 岳麓书院史略 · 第一章 p12–14（已授权）');
    expect(wrapper.text()).toContain('证据缺口');
    expect(wrapper.find('.gap').attributes('class')).toContain('gap');
  });
});
