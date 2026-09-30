import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ClaimBadge from '../components/ClaimBadge.vue';

describe('ClaimBadge', () => {
  it.each([
    ['fact', '●', '事实', '事实陈述，有出处'],
    ['synth', '◐', '归纳', '归纳推断，基于出处综合'],
    ['gen', '○', '生成', '生成内容，仅供参考'],
  ])('%s 档渲染符号+文案+读屏释义', (type, symbol, label, aria) => {
    const wrapper = mount(ClaimBadge, { props: { type } });
    expect(wrapper.text()).toContain(symbol);
    expect(wrapper.text()).toContain(label);
    expect(wrapper.attributes('aria-label')).toBe(aria);
  });
});
