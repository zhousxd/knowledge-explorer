import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CardEditView from '../views/CardEditView.vue';
import { ApiError } from '../api/http';
import type { WbCardDetail } from '../api/types';

const { createCardMock, getWbCardMock, saveContentMock, pushMock, routeParams } = vi.hoisted(() => ({
  createCardMock: vi.fn(),
  getWbCardMock: vi.fn(),
  saveContentMock: vi.fn(),
  pushMock: vi.fn(),
  routeParams: { params: {} as Record<string, string> }
}));

vi.mock('../api/cards', () => ({
  createCard: createCardMock,
  getWbCard: getWbCardMock,
  saveCardContent: saveContentMock
}));

// CardEditView 只消费 useRoute/useRouter(返回按钮与保存后跳转)
vi.mock('vue-router', () => ({
  useRoute: () => routeParams,
  useRouter: () => ({ push: pushMock })
}));

const DETAIL: WbCardDetail = {
  id: 7,
  theme: '湖湘文化',
  templateType: 'TEXT',
  title: '岳麓书院',
  status: 'DRAFT',
  content: { summary: '千年学府', sections: [{ h: '缘起', body: '北宋创建。' }], related: [] },
  sources: [{ assetId: null, title: '《书院志》', locator: '第1页', license: null }]
};

async function mountView() {
  const wrapper = mount(CardEditView);
  await flushPromises();
  return wrapper;
}

describe('CardEditView', () => {
  beforeEach(() => {
    createCardMock.mockReset().mockResolvedValue({ cardId: 9 });
    getWbCardMock.mockReset().mockResolvedValue(DETAIL);
    saveContentMock.mockReset().mockResolvedValue({ versionNo: 2 });
    pushMock.mockReset();
    routeParams.params = {};
  });

  it('新建:专题为 shared 字典下拉(3 键,label 显示),默认选中 academy', async () => {
    const wrapper = await mountView();
    const select = wrapper.find('.head-theme');
    expect((select.element as HTMLSelectElement).value).toBe('academy');
    expect(select.findAll('option').map((o) => ({ value: o.element.value, text: o.text() }))).toEqual([
      { value: 'academy', text: '书院地标' },
      { value: 'cuisine', text: '湘菜风物' },
      { value: 'sound', text: '声音科学' }
    ]);
  });

  it('新建:填写表头与正文后提交,createCard 收到组装体并跳回列表', async () => {
    const wrapper = await mountView();
    await wrapper.find('.head-theme').setValue('cuisine');
    await wrapper.find('.head-title').setValue('爱晚亭');
    await wrapper.find('.summary-input').setValue('秋染爱晚亭');
    await wrapper.find('.section-h').setValue('缘起');
    await wrapper.find('.section-body').setValue('取杜牧诗意命名。');

    await wrapper.find('.save-btn').trigger('click');
    await flushPromises();

    expect(createCardMock).toHaveBeenCalledTimes(1);
    expect(createCardMock).toHaveBeenCalledWith({
      theme: 'cuisine',
      templateType: 'TEXT',
      title: '爱晚亭',
      content: { summary: '秋染爱晚亭', sections: [{ h: '缘起', body: '取杜牧诗意命名。' }], related: [] },
      sources: []
    });
    expect(pushMock).toHaveBeenCalledWith('/cards');
  });

  it('新建:表头/表单未填时不提交并给出行内与页顶提示', async () => {
    const wrapper = await mountView();
    await wrapper.find('.save-btn').trigger('click');

    expect(createCardMock).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
    // 专题已默认选中字典键,表头缺的是标题
    expect(wrapper.find('.alert').text()).toContain('请填写标题');
    // 编辑器行内错误也在页面内可见(summary 空 → 摘要必填)
    expect(wrapper.text()).toContain('请填写摘要');
  });

  it('编辑模式:挂载调 getWbCard 并回填 content/sources,保存走 PUT 新版本', async () => {
    routeParams.params = { id: '7' };
    const wrapper = await mountView();

    expect(getWbCardMock).toHaveBeenCalledWith(7);
    // 回填:只读表头 + 编辑器字段
    expect(wrapper.find('.head-title-text').text()).toBe('岳麓书院');
    expect((wrapper.find('.summary-input').element as HTMLTextAreaElement).value).toBe('千年学府');
    expect((wrapper.find('.source-title').element as HTMLInputElement).value).toBe('《书院志》');

    await wrapper.find('.save-btn').trigger('click');
    await flushPromises();

    expect(createCardMock).not.toHaveBeenCalled();
    expect(saveContentMock).toHaveBeenCalledWith(7, {
      content: { summary: '千年学府', sections: [{ h: '缘起', body: '北宋创建。' }], related: [] },
      sources: [{ assetId: null, title: '《书院志》', locator: '第1页', license: null }]
    });
    expect(pushMock).toHaveBeenCalledWith('/cards');
  });

  it('后端校验失败:ApiError message(含字段路径)显示在页顶 alert 且不跳转', async () => {
    createCardMock.mockRejectedValue(new ApiError(400, 'summary: 个数必须在0和120之间', 'trace-1'));
    const wrapper = await mountView();
    await wrapper.find('.head-theme').setValue('academy');
    await wrapper.find('.head-title').setValue('爱晚亭');
    await wrapper.find('.summary-input').setValue('合规摘要');
    await wrapper.find('.section-h').setValue('缘起');
    await wrapper.find('.section-body').setValue('正文。');

    await wrapper.find('.save-btn').trigger('click');
    await flushPromises();

    expect(wrapper.find('.alert').text()).toContain('summary');
    expect(pushMock).not.toHaveBeenCalled();
  });

  it('加载失败:页顶 alert 展示错误信息', async () => {
    routeParams.params = { id: '7' };
    getWbCardMock.mockRejectedValue(new ApiError(403, '仅可操作自己维护的卡片'));
    const wrapper = await mountView();
    expect(wrapper.find('.alert').text()).toContain('仅可操作自己维护的卡片');
  });
});
