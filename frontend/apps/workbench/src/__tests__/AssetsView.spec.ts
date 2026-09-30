import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AssetsView from '../views/AssetsView.vue';
import type { AssetItem, CitationItem, MeResp } from '../api/types';

const {
  listAssetsMock,
  importAssetsMock,
  listCitationsMock,
  successMock,
  errorMock,
  warningMock,
  authStateMock
} = vi.hoisted(() => ({
  listAssetsMock: vi.fn(),
  importAssetsMock: vi.fn(),
  listCitationsMock: vi.fn(),
  successMock: vi.fn(),
  errorMock: vi.fn(),
  warningMock: vi.fn(),
  authStateMock: { user: null as MeResp | null }
}));

vi.mock('../api/assets', () => ({
  listAssets: listAssetsMock,
  importAssets: importAssetsMock,
  listCitations: listCitationsMock
}));

// 只替换 ElMessage(命令式弹窗挂在 body,组件树里断言不到);其余(含 default 插件)保持原样
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    ElMessage: { success: successMock, error: errorMock, warning: warningMock }
  };
});

vi.mock('../stores/auth', () => ({ useAuthStore: () => authStateMock }));

const ROWS: AssetItem[] = [
  {
    id: 1,
    kind: 'book',
    title: '《岳麓书院史略》',
    sourceMeta: { author: '杨慎初', press: '湖南大学出版社' },
    locator: { chapter: '第一章', pages: '12-14' },
    license: '已授权',
    licenseExpire: '2024-06-30',
    expired: true,
    citationCount: 3
  },
  {
    id: 2,
    kind: 'article',
    title: '《岳麓书院学规探析》',
    sourceMeta: { journal: '湖湘文化研究', year: '2023' },
    locator: { pages: '45-52' },
    license: '已授权',
    licenseExpire: null,
    expired: false,
    citationCount: 0
  },
  {
    id: 3,
    kind: 'video',
    title: '《岳麓书院纪录片》',
    sourceMeta: { producer: '湖南广电' },
    locator: { t: '00:03:10-00:05:45' },
    license: '已授权',
    licenseExpire: '2099-12-31',
    expired: false,
    citationCount: 2
  }
];

const CITATIONS: CitationItem[] = [
  { id: 11, objectType: 'card_version', objectId: 5, quote: '千年学府,弦歌不绝', locator: { chapter: '第一章' } }
];

function mockList(items: AssetItem[] = ROWS) {
  listAssetsMock.mockResolvedValue({ items, total: 3, page: 0, size: 20 });
}

async function mountView() {
  const wrapper = mount(AssetsView, { global: { plugins: [ElementPlus] } });
  await flushPromises();
  return wrapper;
}

function pickFile(wrapper: ReturnType<typeof mount>, name: string, content: string) {
  const input = wrapper.find('input.file-input');
  const file = new File([content], name, { type: 'text/csv' });
  Object.defineProperty(input.element, 'files', { value: [file] });
  return { input, file };
}

describe('AssetsView', () => {
  beforeEach(() => {
    listAssetsMock.mockReset();
    importAssetsMock.mockReset().mockResolvedValue({ imported: 2, skipped: 1, errors: [] });
    listCitationsMock.mockReset().mockResolvedValue([]);
    successMock.mockReset();
    errorMock.mockReset();
    warningMock.mockReset();
    authStateMock.user = { id: 1, nickname: '阿编', role: 'EDITOR' };
    mockList();
  });

  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  it('渲染 3 行:类型 chip 文案/expired 红色「已到期」tag/授权到期日/被引数字/来源与定位摘要', async () => {
    const wrapper = await mountView();
    const rows = wrapper.findAll('.el-table__row');
    expect(rows).toHaveLength(3);
    expect(rows[0]?.text()).toContain('《岳麓书院史略》');

    // 类型 chip:内置 tag type 区分(书=primary/文章=success/视频=danger)
    const kindTag = (i: number) => rows[i]?.find('.kind-tag');
    expect(kindTag(0)?.text()).toBe('书');
    expect(kindTag(0)?.classes()).toContain('el-tag--primary');
    expect(kindTag(1)?.text()).toBe('文章');
    expect(kindTag(1)?.classes()).toContain('el-tag--success');
    expect(kindTag(2)?.text()).toBe('视频');
    expect(kindTag(2)?.classes()).toContain('el-tag--danger');

    // 授权:license + 到期日;expired=true 行追加红色「已到期」tag,未到期行无
    expect(rows[0]?.find('.expire-tag').classes()).toContain('el-tag--danger');
    expect(rows[0]?.find('.expire-tag').text()).toBe('已到期');
    expect(rows[0]?.text()).toContain('2024-06-30');
    expect(rows[1]?.find('.expire-tag').exists()).toBe(false);
    expect(rows[2]?.find('.expire-tag').exists()).toBe(false);

    // 来源摘要(k:v 空格连接)与定位摘要(chapter/t 优先)
    expect(rows[0]?.text()).toContain('author:杨慎初');
    expect(rows[0]?.text()).toContain('第一章');
    expect(rows[2]?.text()).toContain('00:03:10-00:05:45');

    // 被引次数列(tabular-nums),初始请求不带筛选
    expect(rows[0]?.find('.cite-count').text()).toBe('3');
    expect(rows[1]?.find('.cite-count').text()).toBe('0');
    expect(listAssetsMock).toHaveBeenCalledWith({ kind: '', q: '', page: 1, size: 20 });
  });

  it('kind 下拉筛选选择 book 后带 kind 参数重新请求并回到第一页', async () => {
    const wrapper = await mountView();
    listAssetsMock.mockClear();
    await wrapper.find('.kind-select .el-select__wrapper').trigger('click');
    await flushPromises();
    await wrapper.find('[data-kind="book"]').trigger('click');
    await flushPromises();
    expect(listAssetsMock).toHaveBeenCalledTimes(1);
    expect(listAssetsMock).toHaveBeenCalledWith({ kind: 'book', q: '', page: 1, size: 20 });

    // 回「全部」时不带 kind
    await wrapper.find('.kind-select .el-select__wrapper').trigger('click');
    await flushPromises();
    await wrapper.find('[data-kind="ALL"]').trigger('click');
    await flushPromises();
    expect(listAssetsMock).toHaveBeenLastCalledWith({ kind: '', q: '', page: 1, size: 20 });
  });

  it('搜索输入防抖 300ms 后带 q 重新请求', async () => {
    vi.useFakeTimers();
    const wrapper = await mountView();
    listAssetsMock.mockClear();
    await wrapper.find('.search input').setValue('书院');
    expect(listAssetsMock).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(300);
    await flushPromises();
    expect(listAssetsMock).toHaveBeenCalledTimes(1);
    expect(listAssetsMock).toHaveBeenCalledWith({ kind: '', q: '书院', page: 1, size: 20 });
  });

  it('点击被引数字打开引用抽屉并加载 listCitations', async () => {
    listCitationsMock.mockResolvedValue(CITATIONS);
    const wrapper = await mountView();
    await wrapper.findAll('.el-table__row')[0]?.find('.cite-count').trigger('click');
    await flushPromises();
    expect(listCitationsMock).toHaveBeenCalledWith(1);
    const drawer = wrapper.find('.el-drawer');
    expect(drawer.exists()).toBe(true);
    expect(drawer.text()).toContain('card_version');
    expect(drawer.text()).toContain('#5');
    expect(drawer.text()).toContain('千年学府,弦歌不绝');
  });

  it('上传 CSV:调用 importAssets,成功消息 + 可折叠错误列表(行号+原因),并刷新列表', async () => {
    importAssetsMock.mockResolvedValue({ imported: 2, skipped: 1, errors: [{ line: 3, reason: 'locator 非 JSON' }] });
    const wrapper = await mountView();
    listAssetsMock.mockClear();
    const { input, file } = pickFile(wrapper, 'assets.csv', 'kind,title,locator\nbook,书,"{}"');
    await input.trigger('change');
    await flushPromises();

    expect(importAssetsMock).toHaveBeenCalledTimes(1);
    expect(importAssetsMock).toHaveBeenCalledWith(file);
    // 部分成功:已导入行提示不被错误阻塞
    expect(successMock).toHaveBeenCalledWith('导入 2 条');
    const errArea = wrapper.find('.import-errors');
    expect(errArea.exists()).toBe(true);
    expect(errArea.text()).toContain('第 3 行');
    expect(errArea.text()).toContain('locator 非 JSON');
    // 导入后刷新列表
    expect(listAssetsMock).toHaveBeenCalledTimes(1);
  });

  it('上传全部失败:不弹成功消息,提示警告并渲染错误列表', async () => {
    importAssetsMock.mockResolvedValue({
      imported: 0,
      skipped: 2,
      errors: [
        { line: 2, reason: 'title 必填' },
        { line: 3, reason: 'kind 非法: map(允许 book/article/audio/video)' }
      ]
    });
    const wrapper = await mountView();
    const { input } = pickFile(wrapper, 'bad.csv', 'kind,title,locator\nbook,\nmap,x');
    await input.trigger('change');
    await flushPromises();

    expect(successMock).not.toHaveBeenCalled();
    expect(warningMock).toHaveBeenCalledTimes(1);
    const errArea = wrapper.find('.import-errors');
    expect(errArea.text()).toContain('第 2 行');
    expect(errArea.text()).toContain('第 3 行');
  });

  it('上传请求失败:弹错误消息,不渲染错误折叠区', async () => {
    importAssetsMock.mockRejectedValue(new Error('上传文件为空'));
    const wrapper = await mountView();
    const { input } = pickFile(wrapper, 'x.csv', 'kind,title,locator');
    await input.trigger('change');
    await flushPromises();
    expect(errorMock).toHaveBeenCalledWith('上传文件为空');
    expect(wrapper.find('.import-errors').exists()).toBe(false);
  });

  it('上传按钮按角色显隐:EDITOR/OPERATOR 可见,CREATOR 不可见', async () => {
    const editor = await mountView();
    expect(editor.find('.import-btn').exists()).toBe(true);
    editor.unmount();

    authStateMock.user = { id: 2, nickname: '阿运', role: 'OPERATOR' };
    const operator = await mountView();
    expect(operator.find('.import-btn').exists()).toBe(true);
    operator.unmount();

    authStateMock.user = { id: 3, nickname: '阿创', role: 'CREATOR' };
    const creator = await mountView();
    expect(creator.find('.import-btn').exists()).toBe(false);
    expect(creator.find('input.file-input').exists()).toBe(false);
  });

  it('空列表时展示导入引导文案', async () => {
    mockList([]);
    listAssetsMock.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 });
    const wrapper = await mountView();
    expect(wrapper.find('.el-table__empty-text').text()).toContain('通过上方按钮导入知识单元 CSV');
  });
});
