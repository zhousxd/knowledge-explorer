import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import SourcesEditor from '../../components/editors/SourcesEditor.vue';

const TWO_SOURCES = [
  { assetId: 12, title: '《岳麓书院志》', locator: '第12页', license: '已授权' },
  { assetId: null, title: '口述访谈', locator: '录音 03:00', license: null }
];

function lastPayload(wrapper: ReturnType<typeof mount>): Array<Record<string, unknown>> {
  const emitted = wrapper.emitted('update:modelValue');
  expect(emitted).toBeTruthy();
  return emitted![emitted!.length - 1][0] as Array<Record<string, unknown>>;
}

describe('SourcesEditor', () => {
  it('两行来源时提示「引用索引 1-2」,删除第 1 行后提示与载荷联动更新', async () => {
    const wrapper = mount(SourcesEditor, { props: { modelValue: TWO_SOURCES } });
    expect(wrapper.find('.index-hint').text()).toContain('引用索引 1-2');

    await wrapper.findAll('.del-btn')[0]!.trigger('click');
    expect(wrapper.find('.index-hint').text()).toContain('引用索引 1-1');

    // 载荷只剩原第 2 行(口述访谈,assetId null 保持 null)
    const payload = lastPayload(wrapper);
    expect(payload).toHaveLength(1);
    expect(payload[0]!.title).toBe('口述访谈');
    expect(payload[0]!.assetId).toBeNull();
  });

  it('行内校验:缺题名/定位给出行内错误;assetId 规范化(空 → null)', async () => {
    const wrapper = mount(SourcesEditor, { props: { modelValue: [] } });
    // 空来源提示
    expect(wrapper.find('.index-hint').text()).toContain('暂无来源');

    await wrapper.find('.add-btn').trigger('click');
    expect(wrapper.text()).toContain('sources[0].title');
    expect(wrapper.text()).toContain('sources[0].locator');
    expect(wrapper.find('.index-hint').text()).toContain('引用索引 1-1');

    await wrapper.find('.source-title').setValue('《测试书》');
    await wrapper.find('.source-locator').setValue('第1页');
    await wrapper.find('.source-asset').setValue('7');
    const payload = lastPayload(wrapper);
    expect(payload[0]).toEqual({ assetId: 7, title: '《测试书》', locator: '第1页', license: null });
  });
});
