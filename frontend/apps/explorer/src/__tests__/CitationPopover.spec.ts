import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import CitationPopover from '../components/CitationPopover.vue';

describe('CitationPopover(出处浮层,P3-15)', () => {
  it('渲染 [n] + 题名 + 定位次行 + license 小字', () => {
    const w = mount(CitationPopover, {
      props: { index: 2, source: { title: '《岳麓书院史略》', locator: '第一章 p12', license: '已授权' } }
    });
    expect(w.find('.idx').text()).toBe('[2]');
    expect(w.find('.t').text()).toBe('《岳麓书院史略》');
    expect(w.find('.loc').text()).toBe('第一章 p12');
    expect(w.find('.lic').text()).toBe('已授权');
  });

  it('无 license 时不渲染 license 小字', () => {
    const w = mount(CitationPopover, {
      props: { index: 1, source: { title: '湖南大学岳麓书院官网', locator: '书院沿革' } }
    });
    expect(w.find('.t').text()).toBe('湖南大学岳麓书院官网');
    expect(w.find('.lic').exists()).toBe(false);
  });
});
