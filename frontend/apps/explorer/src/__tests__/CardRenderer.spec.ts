import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import {
  CARD_TYPE_LABELS,
  CardRenderer
} from '../components/CardRenderer';
import CompareCard from '../components/CardRenderer/CompareCard.vue';
import TaskCard from '../components/CardRenderer/TaskCard.vue';
import TextCard from '../components/CardRenderer/TextCard.vue';
import TimelineCard from '../components/CardRenderer/TimelineCard.vue';

/** 分发器只按 templateType 选组件,content 形状由各渲染器自己的测试覆盖 */
const STUB_CONTENT = { marker: true };

describe('CardRenderer(按 templateType 分发,02 §8)', () => {
  it('TEXT/COMPARE/TIMELINE/TASK 分别渲染对应渲染器', () => {
    const cases = [
      { type: 'TEXT', comp: TextCard },
      { type: 'COMPARE', comp: CompareCard },
      { type: 'TIMELINE', comp: TimelineCard },
      { type: 'TASK', comp: TaskCard }
    ] as const;
    for (const { type, comp } of cases) {
      const wrapper = mount(CardRenderer, {
        props: { templateType: type, content: STUB_CONTENT }
      });
      expect(wrapper.findComponent(comp).exists(), `分发到 ${type}`).toBe(true);
      wrapper.unmount();
    }
  });

  it('未知类型显示「暂不支持该卡型」兜底文案', () => {
    const wrapper = mount(CardRenderer, {
      props: { templateType: 'MINDMAP', content: STUB_CONTENT }
    });
    expect(wrapper.text()).toContain('暂不支持该卡型');
    expect(wrapper.findComponent(TextCard).exists()).toBe(false);
  });

  it('模板类型中文 chip 文案:图文卡/对比卡/时间线卡/任务卡', () => {
    expect(CARD_TYPE_LABELS.TEXT).toBe('图文卡');
    expect(CARD_TYPE_LABELS.COMPARE).toBe('对比卡');
    expect(CARD_TYPE_LABELS.TIMELINE).toBe('时间线卡');
    expect(CARD_TYPE_LABELS.TASK).toBe('任务卡');
  });
});
