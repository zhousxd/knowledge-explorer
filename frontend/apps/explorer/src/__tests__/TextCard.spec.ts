import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { TextContent } from '../api/cards';
import TextCard from '../components/CardRenderer/TextCard.vue';

const CONTENT: TextContent = {
  summary: '中国四大书院之一，北宋创办。',
  sections: [
    { h: '书院的由来', body: '北宋开宝九年创办于岳麓山下。', citations: [1, 2] },
    { h: '朱张会讲', body: '开创了公开论辩的传统。', citations: [] }
  ],
  related: [{ cardId: 9, relation: '相关联', why: '朱熹与张栻的人物细节', source: 1 }]
};

describe('TextCard(图文卡正文,04 §7.2 KCard)', () => {
  it('渲染 sections 标题与正文,citations 角标文本 [1]/[2]', () => {
    const wrapper = mount(TextCard, { props: { content: CONTENT } });
    expect(wrapper.text()).toContain('书院的由来');
    expect(wrapper.text()).toContain('北宋开宝九年创办于岳麓山下。');
    const cites = wrapper.findAll('sup').map((s) => s.text());
    expect(cites).toEqual(['[1]', '[2]']); // 第二段 citations 为空,无角标
  });

  it('点击角标 emit cite(n)', async () => {
    const wrapper = mount(TextCard, { props: { content: CONTENT } });
    await wrapper.find('sup').trigger('click');
    expect(wrapper.emitted('cite')?.[0]).toEqual([1]);
  });

  it('related 渲染「相关联」行(why),点击 emit open(cardId)', async () => {
    const wrapper = mount(TextCard, { props: { content: CONTENT } });
    const row = wrapper.find('.rel-row');
    expect(row.exists()).toBe(true);
    expect(row.text()).toContain('朱熹与张栻的人物细节');
    expect(row.text()).toContain('相关联');
    await row.trigger('click');
    expect(wrapper.emitted('open')?.[0]).toEqual([9]);
  });

  it('无 related 时不渲染相关联区块', () => {
    const wrapper = mount(TextCard, {
      props: { content: { ...CONTENT, related: undefined } }
    });
    expect(wrapper.find('.related').exists()).toBe(false);
  });
});
