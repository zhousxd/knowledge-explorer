import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/http';
import { fetchMyEntries } from '../api/entries';
import type { WorkbenchEntry } from '../api/entries';
import EntryStudioView from '../views/EntryStudioView.vue';

vi.mock('../api/entries', () => ({ fetchMyEntries: vi.fn() }));

const mockedFetch = vi.mocked(fetchMyEntries);

function entry(patch: Partial<WorkbenchEntry>): WorkbenchEntry {
  return {
    id: 1, name: '讲讲岳麓书院', type: 'AGENT_SERVICE', serviceType: 'EXPLAIN',
    relationLabel: null, targetCardId: null, scope: 'PRIVATE', status: 'ACTIVE',
    testTotal: 0, cardId: 7, cardTitle: '岳麓书院卡', ...patch
  };
}

async function mountView(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(EntryStudioView, {
    global: { plugins: [ElementPlus], stubs: { RouterLink: true } }
  });
  await flushPromises();
  return wrapper;
}

describe('EntryStudioView(入口编排,Task 28)', () => {
  beforeEach(() => {
    mockedFetch.mockReset();
  });

  it('渲染我的入口表:名称/类型/所属卡/范围/状态/试运行次数', async () => {
    mockedFetch.mockResolvedValue([
      entry({ id: 1 }),
      entry({ id: 2, name: '学规下篇', type: 'LINK_CARD', serviceType: null, targetCardId: 9,
        relationLabel: '深入了解', scope: 'PUBLIC', status: 'DISABLED', testTotal: 3 })
    ]);
    const wrapper = await mountView();
    const rows = wrapper.findAll('tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0]!.text()).toContain('讲讲岳麓书院');
    expect(rows[0]!.text()).toContain('智能体服务 · EXPLAIN');
    expect(rows[0]!.text()).toContain('岳麓书院卡');
    expect(rows[0]!.text()).toContain('个人空间');
    expect(rows[0]!.text()).toContain('生效中');
    expect(rows[0]!.text()).toContain('0');
    expect(rows[1]!.text()).toContain('链接入口');
    expect(rows[1]!.text()).toContain('公共区');
    expect(rows[1]!.text()).toContain('已停用');
    expect(rows[1]!.text()).toContain('3');
  });

  it('状态 tabs 过滤:已停用只留 DISABLED 行,计数联动', async () => {
    mockedFetch.mockResolvedValue([
      entry({ id: 1, status: 'ACTIVE' }),
      entry({ id: 2, status: 'DISABLED' })
    ]);
    const wrapper = await mountView();
    expect(wrapper.find('[data-tab="ALL"]').text()).toContain('(2)');
    await wrapper.find('[data-tab="DISABLED"]').trigger('click');
    await flushPromises();
    const rows = wrapper.findAll('tbody tr');
    expect(rows).toHaveLength(1);
    expect(rows[0]!.text()).toContain('已停用');
    await wrapper.find('[data-tab="ALL"]').trigger('click');
    await flushPromises();
    expect(wrapper.findAll('tbody tr')).toHaveLength(2);
  });

  it('详情抽屉:全字段只读简版', async () => {
    mockedFetch.mockResolvedValue([entry({ id: 2, name: '学规下篇', type: 'LINK_CARD', serviceType: null,
      targetCardId: 9, relationLabel: '深入了解', scope: 'PUBLIC', status: 'ACTIVE', testTotal: 3 })]);
    const wrapper = await mountView();
    await wrapper.find('.detail-btn').trigger('click');
    await flushPromises();
    const drawer = wrapper.find('.el-drawer');
    expect(drawer.exists()).toBe(true);
    expect(drawer.text()).toContain('学规下篇');
    expect(drawer.text()).toContain('链接入口');
    expect(drawer.text()).toContain('深入了解');
    expect(drawer.text()).toContain('3');
  });

  it('空态与加载失败', async () => {
    mockedFetch.mockResolvedValue([]);
    let wrapper = await mountView();
    expect(wrapper.find('.empty').text()).toContain('还没有入口');

    mockedFetch.mockRejectedValue(new ApiError(500, '服务异常', 't'));
    wrapper = await mountView();
    expect(wrapper.find('.load-error').text()).toContain('服务异常');
  });
});
