import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { TimelineContent } from '../api/cards';
import TimelineCard from '../components/CardRenderer/TimelineCard.vue';

const CONTENT: TimelineContent = {
  events: [
    { year: '976 年', title: '开宝九年创办', body: '潭州太守朱洞创建。', cardId: 5, citations: [1] },
    { year: '1167 年', title: '朱张会讲', body: '朱熹与张栻公开论辩。' }
  ]
};

describe('TimelineCard(时间线卡,04 §7.2 Timeline)', () => {
  it('渲染全部事件(年份/标题/正文)', () => {
    const wrapper = mount(TimelineCard, { props: { content: CONTENT } });
    const items = wrapper.findAll('.tl-item');
    expect(items).toHaveLength(2);
    expect(items[0].text()).toContain('976 年');
    expect(items[0].text()).toContain('开宝九年创办');
    expect(items[0].text()).toContain('潭州太守朱洞创建。');
  });

  it('首个事件 hl(实心圆点),其余事件无 hl', () => {
    const wrapper = mount(TimelineCard, { props: { content: CONTENT } });
    const items = wrapper.findAll('.tl-item');
    expect(items[0].classes()).toContain('hl');
    expect(items[1].classes()).not.toContain('hl');
  });

  it('带 cardId 的事件可点 emit open(cardId),无 cardId 不触发', async () => {
    const wrapper = mount(TimelineCard, { props: { content: CONTENT } });
    const items = wrapper.findAll('.tl-item');
    await items[0].trigger('click');
    expect(wrapper.emitted('open')?.[0]).toEqual([5]);
    await items[1].trigger('click');
    expect(wrapper.emitted('open')).toHaveLength(1);
  });

  it('事件 citations 角标点击 emit cite(n)', async () => {
    const wrapper = mount(TimelineCard, { props: { content: CONTENT } });
    await wrapper.find('sup').trigger('click');
    expect(wrapper.emitted('cite')?.[0]).toEqual([1]);
  });
});
