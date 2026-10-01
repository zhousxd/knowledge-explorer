import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { ApiError } from '../api/http';
import { continueShare, fetchPublicShare } from '../api/shares';
import type { PublicShare, ShareSnapshotNode } from '../api/shares';
import ShareView from '../views/ShareView.vue';

vi.mock('../api/shares', () => ({ fetchPublicShare: vi.fn(), continueShare: vi.fn() }));
const mockedFetch = vi.mocked(fetchPublicShare);
const mockedContinue = vi.mocked(continueShare);

let seq = 0;
/** 快照节点构造(缺省=正常已发布节点;removed=true 占位行带 note;title 需显式 null 传参才为 null) */
function snapNode(partial: Partial<ShareSnapshotNode>): ShareSnapshotNode {
  seq += 1;
  return {
    title: partial.title !== undefined ? partial.title : `节点 ${seq}`,
    cardVersionId: partial.cardVersionId ?? null,
    entries: partial.entries ?? null,
    question: partial.question ?? null,
    visitedAt: partial.visitedAt ?? '2026-09-28T09:00:00+08:00',
    removed: partial.removed ?? false,
    note: partial.note ?? null,
    nodeRef: partial.nodeRef ?? seq,
    parentNodeRef: partial.parentNodeRef ?? null
  };
}

/** P7-31 冻结契约响应:GET /s/{token} → {token,title,summary,snapshot,createdAt,continueNotice} */
const SHARE: PublicShare = {
  token: 'tok9abc',
  title: '读懂岳麓书院',
  summary: '一次从选址到藏书的探索',
  snapshot: {
    title: '读懂岳麓书院',
    summary: '一次从选址到藏书的探索',
    generatedAt: '2026-09-30T10:00:00+08:00',
    nodes: [
      snapNode({
        title: '岳麓书院：从选址到人物',
        cardVersionId: 11,
        entries: [{ name: '书院寻踪', relationLabel: '坐落于' }],
        nodeRef: 1
      }),
      snapNode({ title: '为什么建在这里', cardVersionId: 12, entries: [], nodeRef: 2, parentNodeRef: 1 }),
      snapNode({ title: null, question: '朱张会讲是谁主持的？', nodeRef: 3, parentNodeRef: 2 }),
      snapNode({ title: '已下架的一张卡', removed: true, note: '内容已不可用', nodeRef: 4, parentNodeRef: 2 })
    ]
  },
  createdAt: '2026-09-30T10:00:00+08:00',
  continueNotice: '来源与模型可能已更新，接续后的结果或有差异'
};

/** 直挂 ShareView 的独立 memory 路由(/path /login 兜底供跳转断言) */
async function mountShare(): Promise<{ wrapper: Awaited<ReturnType<typeof mount>>; local: ReturnType<typeof createRouter> }> {
  const local = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/s/:token', component: ShareView },
      { path: '/path', component: { render: () => null } },
      { path: '/login', component: { render: () => null } }
    ]
  });
  await local.push('/s/tok9abc');
  await local.isReady();
  const wrapper = mount(ShareView, { global: { plugins: [local] } });
  await flushPromises();
  onTestFinished(() => {
    try {
      wrapper.unmount();
    } catch {
      // 用例内已手动卸载
    }
  });
  return { wrapper, local };
}

describe('ShareView(免登录分享页/接收者视角,FR-H04)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    mockedFetch.mockResolvedValue(SHARE);
    mockedContinue.mockResolvedValue({ sessionId: 9, nodeCount: 3 });
  });

  it('200 渲染:宋体标题+摘要+来源小字+continueNotice 提示条(逐字)', async () => {
    const { wrapper } = await mountShare();
    expect(mockedFetch).toHaveBeenCalledWith('tok9abc');

    expect(wrapper.find('.sv-title').text()).toBe('读懂岳麓书院');
    expect(wrapper.find('.sv-summary').text()).toBe('一次从选址到藏书的探索');
    expect(wrapper.find('.sv-source').text()).toBe('来源:探索分享');
    expect(wrapper.find('.sv-notice').exists()).toBe(true);
    expect(wrapper.find('.sv-notice').text()).toContain('来源与模型可能已更新，接续后的结果或有差异');
    // P8-36 合规硬门槛:页脚 AI 生成标识(逐字)
    expect(wrapper.find('.ai-note').text()).toBe('本页内容由人工智能辅助生成，仅供参考');
  });

  it('路径时间线按快照顺序渲染:正常行=题/问+时间+entries 关系列(name · relationLabel)', async () => {
    const { wrapper } = await mountShare();
    const rows = wrapper.findAll('.tl-row');
    expect(rows).toHaveLength(4);

    expect(rows[0]!.find('.tl-t').text()).toBe('岳麓书院：从选址到人物');
    expect(rows[0]!.find('.tl-time').text()).toContain('9月28日');
    expect(rows[0]!.findAll('.tl-entry')).toHaveLength(1);
    expect(rows[0]!.find('.tl-entry').text()).toBe('书院寻踪 · 坐落于');

    // 纯追问节点:标题回落 question 文本
    expect(rows[2]!.find('.tl-t').text()).toBe('朱张会讲是谁主持的？');
    // entries 空数组:无关系列
    expect(rows[1]!.findAll('.tl-entry')).toHaveLength(0);
  });

  it('removed 节点渲染占位灰行(note 文案),不渲染题/时间/关系列', async () => {
    const { wrapper } = await mountShare();
    const removed = wrapper.findAll('.tl-row')[3]!;
    expect(removed.classes()).toContain('removed');
    expect(removed.find('.tl-ph').text()).toBe('内容已不可用');
    expect(removed.find('.tl-t').exists()).toBe(false);
    expect(removed.find('.tl-time').exists()).toBe(false);
    expect(removed.find('.tl-entry').exists()).toBe(false);
  });

  it('404(撤销/不存在):空态「链接不存在或已被撤销」,无时间线无接续条,AI 标识页级常驻', async () => {
    mockedFetch.mockRejectedValue(new ApiError(404, '分享不存在'));
    const { wrapper } = await mountShare();
    expect(wrapper.find('.miss').exists()).toBe(true);
    expect(wrapper.find('.miss-t').text()).toBe('链接不存在或已被撤销');
    expect(wrapper.find('.tl').exists()).toBe(false);
    expect(wrapper.find('.sv-bar').exists()).toBe(false);
    expect(wrapper.find('.ai-note').exists()).toBe(true);
  });

  it('已登录:按钮「沿此路径继续」,点击 continueShare(token) 成功跳 /path?sessionId=9', async () => {
    localStorage.setItem('ke_ex_token', 'tok');
    const { wrapper, local } = await mountShare();
    const btn = wrapper.find('.cont-go');
    expect(btn.text()).toBe('沿此路径继续');

    await btn.trigger('click');
    await flushPromises();
    expect(mockedContinue).toHaveBeenCalledWith('tok9abc');
    expect(local.currentRoute.value.path).toBe('/path');
    expect(local.currentRoute.value.query.sessionId).toBe('9');
  });

  it('匿名:按钮文案「登录后接续」,点击 toast「登录后可接续」跳 /login,不调 continue', async () => {
    const { wrapper, local } = await mountShare();
    const btn = wrapper.find('.cont-go');
    expect(btn.text()).toBe('登录后接续');

    await btn.trigger('click');
    await flushPromises();
    expect(mockedContinue).not.toHaveBeenCalled();
    expect(wrapper.find('.sv-toast').text()).toContain('登录后可接续');
    expect(local.currentRoute.value.path).toBe('/login');
  });

  it('已登录接续失败(404 已撤销):toast 错误信息,停留本页', async () => {
    localStorage.setItem('ke_ex_token', 'tok');
    mockedContinue.mockRejectedValue(new ApiError(404, '分享不存在'));
    const { wrapper, local } = await mountShare();
    await wrapper.find('.cont-go').trigger('click');
    await flushPromises();
    expect(wrapper.find('.sv-toast').text()).toContain('分享不存在');
    expect(local.currentRoute.value.path).toBe('/s/tok9abc');
  });

  it('加载中:先渲染加载态,不渲染内容', async () => {
    let release: (v: PublicShare) => void = () => {};
    mockedFetch.mockReturnValueOnce(new Promise((res) => { release = res; }));
    const local = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/s/:token', component: ShareView }]
    });
    await local.push('/s/tok9abc');
    await local.isReady();
    const wrapper = mount(ShareView, { global: { plugins: [local] } });
    await flushPromises();
    expect(wrapper.find('.sv-state').exists()).toBe(true);
    expect(wrapper.find('.sv-title').exists()).toBe(false);
    release(SHARE);
    await flushPromises();
    expect(wrapper.find('.sv-title').exists()).toBe(true);
    wrapper.unmount();
  });
});
