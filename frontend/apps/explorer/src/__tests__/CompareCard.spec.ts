import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { CompareContent } from '../api/cards';
import CompareCard from '../components/CardRenderer/CompareCard.vue';

const CONTENT: CompareContent = {
  objects: ['岳麓书院', '城南书院'],
  dimensions: ['创办时间', '核心定位'],
  cells: [
    ['976 年（北宋）', '1161 年（南宋）'],
    ['书院教育', '祭祀与教育并重']
  ],
  citations: [3]
};

describe('CompareCard(对比卡表格,04 §7.2 Compare)', () => {
  it('表头 = objects(含首列空角)', () => {
    const wrapper = mount(CompareCard, { props: { content: CONTENT } });
    const heads = wrapper.findAll('thead th').map((th) => th.text());
    expect(heads).toEqual(['', '岳麓书院', '城南书院']);
  });

  it('首列 = dimensions,单元格数值按 (维度, 对象) 落位', () => {
    const wrapper = mount(CompareCard, { props: { content: CONTENT } });
    const rows = wrapper.findAll('tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0].find('th').text()).toBe('创办时间');
    expect(rows[1].find('th').text()).toBe('核心定位');
    const row0 = rows[0].findAll('td').map((td) => td.text());
    expect(row0).toEqual(['976 年（北宋）', '1161 年（南宋）']);
    expect(rows[1].findAll('td')[1].text()).toBe('祭祀与教育并重');
  });

  it('表格包在横滚容器里(移动端 overflow-x)', () => {
    const wrapper = mount(CompareCard, { props: { content: CONTENT } });
    expect(wrapper.find('.cmp-wrap').exists()).toBe(true);
    expect(wrapper.find('table.cmp').exists()).toBe(true);
  });

  it('整卡 citations 角标点击 emit cite(n)', async () => {
    const wrapper = mount(CompareCard, { props: { content: CONTENT } });
    await wrapper.find('sup').trigger('click');
    expect(wrapper.emitted('cite')?.[0]).toEqual([3]);
  });
});
