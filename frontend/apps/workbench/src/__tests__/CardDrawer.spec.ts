import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CardDrawer from '../components/CardDrawer.vue';
import type { CardListItem, VersionItem } from '../api/types';

const { getVersionsMock } = vi.hoisted(() => ({ getVersionsMock: vi.fn() }));

vi.mock('../api/cards', () => ({ getCardVersions: getVersionsMock }));

const CARD: CardListItem = {
  id: 7,
  theme: '湖湘文化',
  templateType: 'TEXT',
  title: '岳麓书院',
  status: 'PUBLISHED',
  currentVersionNo: 2,
  maintainerId: 1,
  maintainerNickname: '阿创',
  updatedAt: '2026-09-30T10:00:00+08:00'
};

const VERSIONS: VersionItem[] = [
  { versionNo: 2, createdByNickname: '阿编', createdAt: '2026-09-29T09:30:00+08:00' },
  { versionNo: 1, createdByNickname: '阿创', createdAt: '2026-09-28T08:00:00+08:00' }
];

async function mountDrawer(card: CardListItem | null = CARD, open = false) {
  const wrapper = mount(CardDrawer, {
    props: { card, open },
    global: { plugins: [ElementPlus] }
  });
  if (open) await flushPromises();
  return wrapper;
}

describe('CardDrawer', () => {
  beforeEach(() => {
    getVersionsMock.mockReset();
  });

  it('打开时拉取版本历史并渲染 2 条时间线(版本号/作者/时间)', async () => {
    getVersionsMock.mockResolvedValue(VERSIONS);
    const wrapper = await mountDrawer();
    expect(getVersionsMock).not.toHaveBeenCalled();

    await wrapper.setProps({ open: true });
    await flushPromises();
    expect(getVersionsMock).toHaveBeenCalledWith(7);

    const items = wrapper.findAll('.el-timeline-item');
    expect(items).toHaveLength(2);
    // versionNo 倒序:首条为当前版本 v2,末条 v1
    expect(items[0]?.text()).toContain('v2');
    expect(items[0]?.text()).toContain('阿编');
    expect(items[0]?.text()).toContain('2026-09-29 09:30');
    expect(items[1]?.text()).toContain('v1');
    expect(items[1]?.text()).toContain('阿创');
    expect(items[1]?.text()).toContain('2026-09-28 08:00');
    // 卡摘要:宋体标题 + 模板/状态 chips
    expect(wrapper.find('.drawer-title').text()).toBe('岳麓书院');
    expect(wrapper.find('.drawer-title').classes().join(' ')).not.toBe('');
    expect(wrapper.text()).toContain('图文');
    expect(wrapper.text()).toContain('已发布');
  });

  it('时间线只读:任何版本都无操作按钮,仅当前版本带「当前」标记', async () => {
    getVersionsMock.mockResolvedValue(VERSIONS);
    const wrapper = await mountDrawer();
    await wrapper.setProps({ open: true });
    await flushPromises();

    const items = wrapper.findAll('.el-timeline-item');
    expect(items).toHaveLength(2);
    expect(wrapper.findAll('.el-timeline button, .el-timeline .el-button')).toHaveLength(0);
    expect(items[0]?.text()).toContain('当前');
    expect(items[1]?.text()).not.toContain('当前');
  });

  it('版本历史加载失败展示错误信息', async () => {
    getVersionsMock.mockRejectedValue(new Error('服务异常'));
    const wrapper = await mountDrawer();
    await wrapper.setProps({ open: true });
    await flushPromises();
    expect(wrapper.find('.drawer-error').exists()).toBe(true);
    expect(wrapper.find('.drawer-error').text()).toContain('服务异常');
  });

  it('「编辑内容」:非停用卡显示,PUBLISHED 注明存新版本,点击 emit edit(id)', async () => {
    getVersionsMock.mockResolvedValue(VERSIONS);
    const wrapper = await mountDrawer();
    await wrapper.setProps({ open: true });
    await flushPromises();

    // PUBLISHED 卡:编辑入口 + 「将生成新版本」备注
    expect(wrapper.find('.edit-btn').exists()).toBe(true);
    expect(wrapper.find('.edit-note').text()).toContain('将生成新版本');
    await wrapper.find('.edit-btn').trigger('click');
    expect(wrapper.emitted('edit')).toEqual([[7]]);

    // DISABLED 卡:无编辑入口
    await wrapper.setProps({ card: { ...CARD, status: 'DISABLED' } });
    await flushPromises();
    expect(wrapper.find('.edit-btn').exists()).toBe(false);
    expect(wrapper.find('.edit-note').exists()).toBe(false);
  });
});
