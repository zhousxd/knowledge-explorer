import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import TextEditor from '../../components/editors/TextEditor.vue';

const BASE = {
  summary: '岳麓书院简要介绍',
  sections: [{ h: '缘起', body: '北宋开宝九年创建。' }],
  related: []
};

function lastPayload(wrapper: ReturnType<typeof mount>): Record<string, unknown> {
  const emitted = wrapper.emitted('update:modelValue');
  expect(emitted).toBeTruthy();
  return emitted![emitted!.length - 1][0] as Record<string, unknown>;
}

describe('TextEditor', () => {
  it('summary 超 120 字 → 行内错误文案与 n/120 计数', async () => {
    const wrapper = mount(TextEditor, { props: { modelValue: { ...BASE, summary: '字'.repeat(121) }, sourcesCount: 0 } });
    expect(wrapper.find('.summary-error').text()).toContain('超过 120 字');
    expect(wrapper.find('.char-count').text()).toBe('121/120');

    // 修剪回合规 → 错误消失
    await wrapper.find('.summary-input').setValue('合理的摘要');
    expect(wrapper.find('.summary-error').exists()).toBe(false);
  });

  it('citations 输入 "1,2" → 载荷规范化为 [1,2]', async () => {
    const wrapper = mount(TextEditor, { props: { modelValue: BASE, sourcesCount: 2 } });
    await wrapper.find('.citations-input').setValue('1,2');
    const payload = lastPayload(wrapper);
    const sections = payload.sections as Array<{ citations?: number[] }>;
    expect(sections[0]!.citations).toEqual([1, 2]);
  });

  it('citations 输入 "abc" → 行内错误文案且不写入载荷', async () => {
    const wrapper = mount(TextEditor, { props: { modelValue: BASE, sourcesCount: 2 } });
    await wrapper.find('.citations-input').setValue('abc');
    expect(wrapper.text()).toContain('不是数字');
    const sections = lastPayload(wrapper).sections as Array<Record<string, unknown>>;
    expect(sections[0]).not.toHaveProperty('citations');
  });
});
