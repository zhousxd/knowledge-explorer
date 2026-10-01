import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import CompareEditor from '../../components/editors/CompareEditor.vue';

/** 简报指定用例:对比编辑器 objects[]×dimensions[]→cells[][] 网格联动 */

const BASE = {
  objects: ['岳麓书院', '白鹿洞书院'],
  dimensions: ['门票', '特色'],
  cells: [
    ['免费', '收费'],
    ['书院讲学', '祠堂祭祀']
  ]
};

function lastPayload(wrapper: ReturnType<typeof mount>): Record<string, unknown> {
  const emitted = wrapper.emitted('update:modelValue');
  expect(emitted).toBeTruthy();
  return emitted![emitted!.length - 1][0] as Record<string, unknown>;
}

describe('CompareEditor', () => {
  it('初始 2 objects × 2 dimensions 渲染 4 格', () => {
    const wrapper = mount(CompareEditor, { props: { modelValue: BASE, sourcesCount: 0 } });
    expect(wrapper.findAll('.cell')).toHaveLength(4);
    expect(wrapper.findAll('.dim-row')).toHaveLength(2);
  });

  it('加一维度 → 网格 3 行且原 2 行 cells 数据保留', async () => {
    const wrapper = mount(CompareEditor, { props: { modelValue: BASE, sourcesCount: 0 } });
    await wrapper.find('.add-dimension').trigger('click');

    expect(wrapper.findAll('.dim-row')).toHaveLength(3);
    expect(wrapper.findAll('.cell')).toHaveLength(6);
    const payload = lastPayload(wrapper);
    expect(payload.dimensions).toHaveLength(3);
    expect(payload.cells).toEqual([
      ['免费', '收费'],
      ['书院讲学', '祠堂祭祀'],
      ['', '']
    ]);
  });

  it('删一对象 → cells 列收缩且保留存活列数据', async () => {
    const wrapper = mount(CompareEditor, { props: { modelValue: BASE, sourcesCount: 0 } });
    await wrapper.findAll('.obj-del')[0]!.trigger('click');

    expect(wrapper.findAll('.cell')).toHaveLength(2);
    const payload = lastPayload(wrapper);
    expect(payload.objects).toEqual(['白鹿洞书院']);
    expect(payload.cells).toEqual([['收费'], ['祠堂祭祀']]);
  });

  it('citations 输入越界(无来源时填 1)→ 行内错误文案', async () => {
    const wrapper = mount(CompareEditor, { props: { modelValue: BASE, sourcesCount: 0 } });
    await wrapper.find('.citations-input').setValue('1');
    expect(wrapper.find('.field-error').text()).toContain('超出来源范围');
  });

  it('objects/dimensions 空串或纯空白行 → 该行红字「名称不能为空」并计入保存门禁', async () => {
    const wrapper = mount(CompareEditor, {
      props: {
        modelValue: {
          objects: ['', '白鹿洞书院'],
          dimensions: ['门票', '   '],
          cells: [['', ''], ['', '']]
        },
        sourcesCount: 0
      }
    });

    // 行内红字:对象 1 与维度 2 各一条,已填行不提示
    const nameErrors = wrapper.findAll('.name-error');
    expect(nameErrors).toHaveLength(2);
    expect(nameErrors.every((n) => n.text() === '名称不能为空')).toBe(true);

    // errors 汇总纳入空名错误(CardEditView 保存门禁取 editorRef.errors.length)
    const summary = wrapper.find('.field-error').text();
    expect(summary).toContain('对象名称不能为空');
    expect(summary).toContain('维度名称不能为空');

    // 填上名称后行内提示消失,汇总错误区整体收起(errors 清空 → 保存门禁放行)
    await wrapper.find('.obj-input').setValue('岳麓书院');
    await wrapper.findAll('.dim-input')[1]!.setValue('特色');
    expect(wrapper.findAll('.name-error')).toHaveLength(0);
    expect(wrapper.find('.field-error').exists()).toBe(false);
  });
});
