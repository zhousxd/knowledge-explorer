import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import TimelineEditor from '../../components/editors/TimelineEditor.vue';

function lastPayload(wrapper: ReturnType<typeof mount>): Record<string, unknown> {
  const emitted = wrapper.emitted('update:modelValue');
  expect(emitted).toBeTruthy();
  return emitted![emitted!.length - 1][0] as Record<string, unknown>;
}

describe('TimelineEditor', () => {
  it('事件行增删联动载荷,year/title 缺失给出行内错误', async () => {
    const wrapper = mount(TimelineEditor, {
      props: { modelValue: { events: [{ year: '976 年', title: '岳麓书院创建', body: '知州朱洞创立。' }] }, sourcesCount: 0 }
    });
    expect(wrapper.findAll('.row-card')).toHaveLength(1);

    // 加一行 → 载荷 2 条(新行空表单)
    await wrapper.find('.add-btn').trigger('click');
    expect(wrapper.findAll('.row-card')).toHaveLength(2);
    expect(lastPayload(wrapper).events).toHaveLength(2);

    // 新行 year/title 为空 → 行内错误定位到 events[1]
    expect(wrapper.text()).toContain('events[1].year');
    expect(wrapper.text()).toContain('events[1].title');

    // 删掉新增的第 2 行 → 载荷恢复且保留原数据
    await wrapper.findAll('.del-btn')[1]!.trigger('click');
    expect(lastPayload(wrapper).events).toEqual([
      { year: '976 年', title: '岳麓书院创建', body: '知州朱洞创立。' }
    ]);
  });
});
