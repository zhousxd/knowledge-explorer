import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import TaskEditor from '../../components/editors/TaskEditor.vue';

const BASE = {
  goal: '实地考察岳麓书院',
  steps: [{ place: '岳麓书院', observe: '记录匾额与楹联', minutes: 30 }],
  recordSchema: ['文本']
};

function lastPayload(wrapper: ReturnType<typeof mount>): Record<string, unknown> {
  const emitted = wrapper.emitted('update:modelValue');
  expect(emitted).toBeTruthy();
  return emitted![emitted!.length - 1][0] as Record<string, unknown>;
}

describe('TaskEditor', () => {
  it('minutes 非正整数给出行内错误;recordSchema 建议增删联动载荷', async () => {
    const wrapper = mount(TaskEditor, { props: { modelValue: BASE, sourcesCount: 0 } });

    // minutes = 0 → 错误定位到 steps[0].minutes,载荷仍规范化为数字
    await wrapper.find('.step-minutes').setValue('0');
    expect(wrapper.text()).toContain('steps[0].minutes');
    expect((lastPayload(wrapper).steps as Array<{ minutes: number }>)[0]!.minutes).toBe(0);
    await wrapper.find('.step-minutes').setValue('45');
    expect((lastPayload(wrapper).steps as Array<{ minutes: number }>)[0]!.minutes).toBe(45);

    // 内置建议「照片」一键加入 → 载荷 ['文本','照片'];已存在则禁用
    const suggest = wrapper.findAll('.suggest-btn').find((b) => b.text() === '照片')!;
    await suggest.trigger('click');
    expect(lastPayload(wrapper).recordSchema).toEqual(['文本', '照片']);
    expect(suggest.attributes('disabled')).toBeDefined();

    // 删除「文本」 → 载荷只剩 ['照片']
    await wrapper.findAll('.chip-del')[0]!.trigger('click');
    expect(lastPayload(wrapper).recordSchema).toEqual(['照片']);
  });
});
