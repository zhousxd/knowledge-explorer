import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import CitationTag from '../components/CitationTag.vue';

describe('CitationTag', () => {
  it('渲染角标并响应点击', async () => {
    const wrapper = mount(CitationTag, { props: { index: 2 } });
    expect(wrapper.text()).toBe('[2]');
    expect(wrapper.attributes('role')).toBe('button');
    await wrapper.trigger('click');
    expect(wrapper.emitted('click')).toHaveLength(1);
  });
});
