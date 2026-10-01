import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { TaskContent } from '../api/cards';
import TaskCard from '../components/CardRenderer/TaskCard.vue';

const CONTENT: TaskContent = {
  goal: '用半天时间，从建筑读懂书院的秩序。',
  steps: [
    { place: '头门', observe: '为什么轴线偏向东侧？', minutes: 15 },
    { place: '讲堂', observe: '堂内匾额写了什么？', minutes: 30 }
  ],
  recordSchema: ['地点', '一句话观察']
};

describe('TaskCard(任务卡站点,04 §7.2 TaskStop)', () => {
  it('渲染 goal 声明与全部 steps(站名/分钟数/观察问题)', () => {
    const wrapper = mount(TaskCard, { props: { content: CONTENT } });
    expect(wrapper.text()).toContain('用半天时间，从建筑读懂书院的秩序。');
    const stops = wrapper.findAll('.stop');
    expect(stops).toHaveLength(2);
    expect(stops[0].text()).toContain('头门');
    expect(stops[0].text()).toContain('15');
    expect(stops[0].text()).toContain('为什么轴线偏向东侧？');
    expect(stops[1].text()).toContain('讲堂');
    expect(stops[1].text()).toContain('30');
  });

  it('渲染记录字段(recordSchema)', () => {
    const wrapper = mount(TaskCard, { props: { content: CONTENT } });
    expect(wrapper.text()).toContain('记录字段');
    expect(wrapper.text()).toContain('地点');
    expect(wrapper.text()).toContain('一句话观察');
  });

  it('底部固定声明「实时信息不作确定承诺」', () => {
    const wrapper = mount(TaskCard, { props: { content: CONTENT } });
    expect(wrapper.text()).toContain('实时信息不作确定承诺');
  });
});
