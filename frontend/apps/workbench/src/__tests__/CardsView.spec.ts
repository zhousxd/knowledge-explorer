import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import CardsView from '../views/CardsView.vue';
import type { CardListItem } from '../api/types';

const { listCardsMock, getVersionsMock, submitCardMock, disableCardMock, createCardMock } = vi.hoisted(() => ({
  listCardsMock: vi.fn(),
  getVersionsMock: vi.fn(),
  submitCardMock: vi.fn(),
  disableCardMock: vi.fn(),
  createCardMock: vi.fn()
}));

vi.mock('../api/cards', () => ({
  listCards: listCardsMock,
  getCardVersions: getVersionsMock,
  submitCard: submitCardMock,
  disableCard: disableCardMock,
  createCard: createCardMock
}));

const ROWS: CardListItem[] = [
  {
    id: 1,
    theme: '湖湘文化',
    templateType: 'TEXT',
    title: '岳麓书院',
    status: 'PUBLISHED',
    currentVersionNo: 2,
    maintainerNickname: '阿创',
    updatedAt: '2026-09-30T10:00:00+08:00'
  },
  {
    id: 2,
    theme: '湖湘文化',
    templateType: 'COMPARE',
    title: '书院对比',
    status: 'PENDING',
    currentVersionNo: 1,
    maintainerNickname: '阿编',
    updatedAt: '2026-09-29T10:00:00+08:00'
  },
  {
    id: 3,
    theme: '湖湘文化',
    templateType: 'TIMELINE',
    title: '书院年表',
    status: 'DRAFT',
    currentVersionNo: null,
    maintainerNickname: null,
    updatedAt: '2026-09-28T10:00:00+08:00'
  }
];

function mockList(items: CardListItem[] = ROWS) {
  listCardsMock.mockResolvedValue({ items, total: 3, page: 1, size: 20 });
}

async function mountView() {
  const wrapper = mount(CardsView, { global: { plugins: [ElementPlus] } });
  await flushPromises();
  return wrapper;
}

describe('CardsView', () => {
  beforeEach(() => {
    listCardsMock.mockReset();
    getVersionsMock.mockReset().mockResolvedValue([]);
    submitCardMock.mockReset().mockResolvedValue({ cardId: 1, status: 'PENDING' });
    disableCardMock.mockReset().mockResolvedValue({ cardId: 1, status: 'DISABLED' });
    createCardMock.mockReset().mockResolvedValue({ cardId: 99 });
    vi.spyOn(ElMessageBox, 'confirm').mockReset();
    mockList();
  });

  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  it('渲染 3 行卡片:标题/模板 chip/状态 tag/版本/维护人', async () => {
    const wrapper = await mountView();
    const rows = wrapper.findAll('.el-table__row');
    expect(rows).toHaveLength(3);
    expect(rows[0]?.text()).toContain('岳麓书院');

    // 状态 tag 按语义色映射(04 §2.4):已发布=success、待审核=warning、草稿=info
    expect(rows[0]?.find('.el-tag').classes()).toContain('el-tag--success');
    expect(rows[0]?.find('.el-tag').text()).toBe('已发布');
    expect(rows[1]?.find('.el-tag').classes()).toContain('el-tag--warning');
    expect(rows[1]?.find('.el-tag').text()).toBe('待审核');
    expect(rows[2]?.find('.el-tag').classes()).toContain('el-tag--info');
    expect(rows[2]?.find('.el-tag').text()).toBe('草稿');

    // 模板 chip 与版本/维护人列(空值兜底)
    expect(rows[0]?.text()).toContain('图文');
    expect(rows[1]?.text()).toContain('对比');
    expect(rows[2]?.text()).toContain('时间线');
    expect(rows[0]?.text()).toContain('v2');
    expect(rows[2]?.text()).toContain('v—');
    expect(rows[2]?.text()).toContain('—');
    // 初始加载不带筛选
    expect(listCardsMock).toHaveBeenCalledWith({ status: '', q: '', page: 1, size: 20 });
  });

  it('切换状态筛选 chip 后带 status 重新请求并回到第一页', async () => {
    const wrapper = await mountView();
    listCardsMock.mockClear();
    await wrapper.find('[data-status="DRAFT"]').trigger('click');
    await flushPromises();
    expect(listCardsMock).toHaveBeenCalledTimes(1);
    expect(listCardsMock).toHaveBeenCalledWith({ status: 'DRAFT', q: '', page: 1, size: 20 });

    // 回「全部」时不带 status
    await wrapper.find('[data-status="ALL"]').trigger('click');
    await flushPromises();
    expect(listCardsMock).toHaveBeenLastCalledWith({ status: '', q: '', page: 1, size: 20 });
  });

  it('搜索输入防抖 300ms 后带 q 重新请求', async () => {
    vi.useFakeTimers();
    const wrapper = await mountView();
    listCardsMock.mockClear();
    await wrapper.find('.search input').setValue('岳麓');
    // 防抖窗口内不触发
    expect(listCardsMock).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(300);
    await flushPromises();
    expect(listCardsMock).toHaveBeenCalledTimes(1);
    expect(listCardsMock).toHaveBeenCalledWith({ status: '', q: '岳麓', page: 1, size: 20 });
  });

  it('点击行打开详情抽屉并加载版本历史', async () => {
    const wrapper = await mountView();
    await wrapper.findAll('.el-table__row')[0]?.trigger('click');
    await flushPromises();
    expect(wrapper.find('.el-drawer').exists()).toBe(true);
    expect(getVersionsMock).toHaveBeenCalledWith(1);
  });

  it('草稿行「送审」调用 submitCard 并刷新列表', async () => {
    const wrapper = await mountView();
    listCardsMock.mockClear();
    await wrapper.findAll('.el-table__row')[2]?.find('.act-submit').trigger('click');
    await flushPromises();
    expect(submitCardMock).toHaveBeenCalledWith(3);
    expect(listCardsMock).toHaveBeenCalledTimes(1);
  });

  it('已发布行「停用」确认后调用 disableCard,取消则不调用', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    const wrapper = await mountView();
    await wrapper.findAll('.el-table__row')[0]?.find('.act-disable').trigger('click');
    await flushPromises();
    expect(confirmSpy).toHaveBeenCalledTimes(1);
    expect(disableCardMock).toHaveBeenCalledWith(1);

    // 取消分支
    disableCardMock.mockClear();
    confirmSpy.mockRejectedValueOnce('cancel');
    await wrapper.findAll('.el-table__row')[0]?.find('.act-disable').trigger('click');
    await flushPromises();
    expect(disableCardMock).not.toHaveBeenCalled();
  });

  it('新建卡片:必填校验 + 提交后带默认内容创建并刷新', async () => {
    const wrapper = await mountView();
    await wrapper.find('.create-btn').trigger('click');
    const dialog = wrapper.find('.el-dialog');
    expect(dialog.exists()).toBe(true);

    // 未填标题不提交
    await wrapper.find('.create-form input[type="text"]').setValue('湖湘文化');
    await wrapper.find('.btn-create').trigger('click');
    expect(createCardMock).not.toHaveBeenCalled();

    const inputs = wrapper.findAll('.create-form input[type="text"]');
    await inputs[1]?.setValue('爱晚亭');
    await wrapper.find('.btn-create').trigger('click');
    await flushPromises();
    expect(createCardMock).toHaveBeenCalledWith({
      theme: '湖湘文化',
      templateType: 'TEXT',
      title: '爱晚亭',
      content: expect.objectContaining({ summary: '爱晚亭' })
    });
    // 创建后切到草稿筛选刷新
    expect(listCardsMock).toHaveBeenLastCalledWith({ status: 'DRAFT', q: '', page: 1, size: 20 });
  });
});
