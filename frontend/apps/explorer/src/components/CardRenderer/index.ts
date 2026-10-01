/**
 * CardRenderer(02 §8):按 templateType 分发到四模板渲染器,新增卡型只加渲染器。
 * 未知类型渲染「暂不支持该卡型」兜底文案,不白屏。
 */
import { defineComponent, h } from 'vue';
import type { Component } from 'vue';
import CompareCard from './CompareCard.vue';
import TaskCard from './TaskCard.vue';
import TextCard from './TextCard.vue';
import TimelineCard from './TimelineCard.vue';

export { CompareCard, TaskCard, TextCard, TimelineCard };

/** 模板类型 → 中文 chip 文案(04 §7.2 KCard chip 行) */
export const CARD_TYPE_LABELS: Record<string, string> = {
  TEXT: '图文卡',
  COMPARE: '对比卡',
  TIMELINE: '时间线卡',
  TASK: '任务卡'
};

/** 模板类型 → 渲染器组件映射 */
export const CARD_RENDERERS: Record<string, Component> = {
  TEXT: TextCard,
  COMPARE: CompareCard,
  TIMELINE: TimelineCard,
  TASK: TaskCard
};

export const FALLBACK_TEXT = '暂不支持该卡型';

/** 分发组件:content 透传给对应渲染器,cite/open 事件向上冒泡 */
export const CardRenderer = defineComponent({
  name: 'CardRenderer',
  props: {
    templateType: { type: String, required: true },
    content: { type: Object, required: true }
  },
  emits: ['cite', 'open'],
  setup(props, { emit }) {
    return () => {
      const comp = CARD_RENDERERS[props.templateType];
      if (!comp) {
        return h('p', { class: 'cr-fallback' }, FALLBACK_TEXT);
      }
      return h(comp, {
        content: props.content,
        onCite: (n: number) => emit('cite', n),
        onOpen: (cardId: number) => emit('open', cardId)
      });
    };
  }
});
