import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/http';
import { uploadImage } from '../../api/assets';
import TextEditor from '../../components/editors/TextEditor.vue';

vi.mock('../../api/assets', () => ({ uploadImage: vi.fn() }));
const mockedUpload = vi.mocked(uploadImage);

const BASE = {
  summary: '岳麓书院简要介绍',
  sections: [{ h: '缘起', body: '北宋开宝九年创建。' }],
  related: []
};

function lastPayload(wrapper: ReturnType<typeof mount>): Record<string, unknown> {
  const emitted = wrapper.emitted('update:modelValue');
  expect(emitted).toBeTruthy();
  return emitted![emitted!.length - 1][0] as Record<string, unknown>;
}

/** jsdom 里 File 无 createObjectURL/上传真请求:直接向 input 塞 File 后触发 change 走 mock 的 api */
async function pickFile(wrapper: ReturnType<typeof mount>, file: File): Promise<void> {
  const input = wrapper.find('.upload-input, .upload-btn input');
  await input.setValue('');
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
  await input.trigger('change');
}

describe('TextEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('summary 超 120 字 → 行内错误文案与 n/120 计数', async () => {
    const wrapper = mount(TextEditor, { props: { modelValue: { ...BASE, summary: '字'.repeat(121) }, sourcesCount: 0 } });
    expect(wrapper.find('.summary-error').text()).toContain('超过 120 字');
    expect(wrapper.find('.char-count').text()).toBe('121/120');

    // 修剪回合规 → 错误消失
    await wrapper.find('.summary-input').setValue('合理的摘要');
    expect(wrapper.find('.summary-error').exists()).toBe(false);
  });

  it('citations 输入 "1,2" → 载荷规范化为 [1,2]', async () => {
    const wrapper = mount(TextEditor, { props: { modelValue: BASE, sourcesCount: 2 } });
    await wrapper.find('.citations-input').setValue('1,2');
    const payload = lastPayload(wrapper);
    const sections = payload.sections as Array<{ citations?: number[] }>;
    expect(sections[0]!.citations).toEqual([1, 2]);
  });

  it('citations 输入 "abc" → 行内错误文案且不写入载荷', async () => {
    const wrapper = mount(TextEditor, { props: { modelValue: BASE, sourcesCount: 2 } });
    await wrapper.find('.citations-input').setValue('abc');
    expect(wrapper.text()).toContain('不是数字');
    const sections = lastPayload(wrapper).sections as Array<Record<string, unknown>>;
    expect(sections[0]).not.toHaveProperty('citations');
  });

  // —— 配图(二期图片功能) ——

  it('无配图:载荷不含 image 键,显示上传按钮', () => {
    const wrapper = mount(TextEditor, { props: { modelValue: BASE, sourcesCount: 0 } });
    expect(wrapper.find('.upload-btn').exists()).toBe(true);
    expect(lastPayload(wrapper)).not.toHaveProperty('image');
  });

  it('上传成功:预览+alt,载荷带规范 image 引用;alt 空串则省略 alt 键', async () => {
    mockedUpload.mockResolvedValue({ id: 9, url: '/api/images/9', originalName: 'jt.png', contentType: 'image/png', sizeBytes: 95 });
    const wrapper = mount(TextEditor, { props: { modelValue: BASE, sourcesCount: 0 } });
    await pickFile(wrapper, new File(['png'], 'jt.png', { type: 'image/png' }));
    await flushPromises();

    expect(wrapper.find('.image-thumb').attributes('src')).toBe('/api/images/9');
    await wrapper.find('.image-side input').setValue('讲堂');
    let payload = lastPayload(wrapper);
    expect(payload.image).toEqual({ id: 9, url: '/api/images/9', alt: '讲堂' });

    // alt 清空 → 省略 alt 键(后端 alt 可空)
    await wrapper.find('.image-side input').setValue('');
    payload = lastPayload(wrapper);
    expect(payload.image).toEqual({ id: 9, url: '/api/images/9' });
    expect(mockedUpload).toHaveBeenCalledTimes(1);
  });

  it('上传失败:错误文案入 errors 门禁,不写 image', async () => {
    mockedUpload.mockRejectedValue(new ApiError(400, '仅支持 JPG/PNG/GIF/WebP 图片'));
    const wrapper = mount(TextEditor, { props: { modelValue: BASE, sourcesCount: 0 } });
    await pickFile(wrapper, new File(['txt'], 'fake.png', { type: 'image/png' }));
    await flushPromises();

    expect(wrapper.find('.field-error').text()).toContain('仅支持');
    expect(wrapper.find('.upload-btn').exists()).toBe(true); // 仍可重选
    expect(lastPayload(wrapper)).not.toHaveProperty('image');
    const errors = (wrapper.vm as unknown as { errors: string[] }).errors;
    expect(errors.some((e) => e.startsWith('image:'))).toBe(true);
  });

  it('编辑回填已有配图 → 预览+载荷保留;移除 → 载荷去 image 键', async () => {
    const withImage = { ...BASE, image: { id: 3, url: '/api/images/3', alt: '旧图' } };
    const wrapper = mount(TextEditor, { props: { modelValue: withImage, sourcesCount: 0 } });

    expect(wrapper.find('.image-thumb').attributes('src')).toBe('/api/images/3');
    expect(lastPayload(wrapper).image).toEqual({ id: 3, url: '/api/images/3', alt: '旧图' });

    await wrapper.find('.del-btn').trigger('click');
    expect(wrapper.find('.upload-btn').exists()).toBe(true);
    expect(lastPayload(wrapper)).not.toHaveProperty('image');
  });
});
