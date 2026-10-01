import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { createPinia } from 'pinia';
import { showToast } from 'vant';
import { ApiError } from '../api/http';
import { fetchSessionTree } from '../api/sessions';
import type { PathNode, SessionTree } from '../api/sessions';
import { createShare, revokeShare } from '../api/shares';
import ShareSetupView from '../views/ShareSetupView.vue';

vi.mock('../api/sessions', () => ({ fetchSessionTree: vi.fn() }));
vi.mock('../api/shares', () => ({ createShare: vi.fn(), revokeShare: vi.fn() }));
vi.mock('vant', () => ({ showToast: vi.fn() }));
const mockedTree = vi.mocked(fetchSessionTree);
const mockedCreate = vi.mocked(createShare);
const mockedRevoke = vi.mocked(revokeShare);

let seq = 0;
function node(partial: Partial<PathNode>): PathNode {
  seq += 1;
  return {
    nodeId: partial.nodeId ?? seq,
    parentNodeId: partial.parentNodeId ?? null,
    cardVersionId: partial.cardVersionId ?? null,
    entryId: partial.entryId ?? null,
    questionText: partial.questionText ?? null,
    isNewKnowledge: partial.isNewKnowledge ?? false,
    visitedAt: partial.visitedAt ?? '2026-09-30T10:00:00Z',
    cardTitle: partial.cardTitle ?? null
  };
}

/** 两支会话树:A 支 1→2→3(3 为孙节点),B 支 4→5 —— 按根分组勾选(P7-29:勾选集=树内节点) */
const TREE: SessionTree = {
  sessionId: 5,
  theme: 'academy',
  goal: '读懂岳麓书院',
  explainLevel: 'SIMPLE',
  status: 'ACTIVE',
  createdAt: '2026-09-28T09:00:00Z',
  updatedAt: '2026-09-30T10:00:00Z',
  nodes: [
    node({ nodeId: 1, cardTitle: '岳麓书院：从选址到人物', isNewKnowledge: true }),
    node({ nodeId: 2, parentNodeId: 1, cardTitle: '为什么建在这里', isNewKnowledge: true }),
    node({ nodeId: 3, parentNodeId: 2, questionText: '朱张会讲是谁主持的？' }),
    node({ nodeId: 4, cardTitle: '书院与山寺的关系', isNewKnowledge: true }),
    node({ nodeId: 5, parentNodeId: 4, cardTitle: '藏书的去向', isNewKnowledge: true })
  ]
};

/** 直挂 ShareSetupView 的独立 memory 路由(/path 兜底供跳转断言) */
async function mountSetup(query = '?sessionId=5') {
  const pinia = createPinia();
  const local = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/share/new', component: ShareSetupView },
      { path: '/path', component: { render: () => null } },
      { path: '/home', component: { render: () => null } }
    ]
  });
  await local.push({
    path: '/share/new',
    query: query ? Object.fromEntries(new URLSearchParams(query)) : undefined
  });
  await local.isReady();
  const wrapper = mount(ShareSetupView, { global: { plugins: [local, pinia] } });
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

describe('ShareSetupView(分享设置页,FR-H02)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedTree.mockResolvedValue(TREE);
    mockedCreate.mockResolvedValue({ token: 'tok9abc', url: '/s/tok9abc' });
    mockedRevoke.mockResolvedValue({ revoked: true });
  });

  it('载入会话树:按分支分组渲染(2 支 5 行),默认全部勾选(勾选集=树内节点,P7-29)', async () => {
    const { wrapper } = await mountSetup('?sessionId=5');
    expect(mockedTree).toHaveBeenCalledWith(5);

    const branches = wrapper.findAll('.branch');
    expect(branches).toHaveLength(2);
    expect(branches[0]!.find('.b-t').text()).toBe('岳麓书院：从选址到人物');
    expect(branches[0]!.findAll('.n-row')).toHaveLength(2);
    expect(branches[1]!.find('.b-t').text()).toBe('书院与山寺的关系');
    // 默认勾选全部可见节点:5/5 已勾,计数与按钮就绪
    expect(wrapper.find('.sel-count').text()).toContain('已选 5 个节点');
    expect(wrapper.findAll('.ck').every((c) => (c.element as HTMLInputElement).checked)).toBe(true);
    expect(wrapper.find('.sum-go').attributes('disabled')).toBeUndefined();
    // 私密内容提示条(warn soft)在位
    expect(wrapper.find('.hint').text()).toContain('分享前已自动移除私密内容');
    expect(wrapper.find('.hint').text()).toContain('未勾选节点不会包含');
  });

  it('标题预填会话标题、摘要预填空;两者均可编辑', async () => {
    const { wrapper } = await mountSetup('?sessionId=5');
    const title = wrapper.find('.f-title').element as HTMLInputElement;
    expect(title.value).toBe('读懂岳麓书院');
    const summary = wrapper.find('.f-summary').element as HTMLTextAreaElement;
    expect(summary.value).toBe('');

    await wrapper.find('.f-title').setValue('我的书院之旅');
    await wrapper.find('.f-summary').setValue('从选址到藏书');
    expect((wrapper.find('.f-title').element as HTMLInputElement).value).toBe('我的书院之旅');
  });

  it('取消 1 个节点后生成:createShare 带 {objectType,objectId,nodeIds(剔除未选),title,summary};成功展示完整链接+接收者视角入口', async () => {
    const { wrapper } = await mountSetup('?sessionId=5');
    await wrapper.find('.f-title').setValue('我的书院之旅');
    await wrapper.find('.f-summary').setValue('从选址到藏书');

    // 取消 A 支的孙节点 3:未勾选不入快照(P7-29 钉子)
    await wrapper.findAll('.n-row .ck')[1]!.setValue(false);
    await wrapper.find('.sum-go').trigger('click');
    await flushPromises();

    expect(mockedCreate).toHaveBeenCalledTimes(1);
    expect(mockedCreate).toHaveBeenCalledWith({
      objectType: 'SESSION',
      objectId: 5,
      nodeIds: [1, 2, 4, 5],
      title: '我的书院之旅',
      summary: '从选址到藏书'
    });
    // 完整链接=origin+url 路径;复制按钮与「打开接收者视角」(新标签)在位
    const link = wrapper.find('.link');
    expect((link.element as HTMLInputElement).value).toBe('http://localhost:3000/s/tok9abc');
    const open = wrapper.find('.open');
    expect(open.attributes('href')).toBe('http://localhost:3000/s/tok9abc');
    expect(open.attributes('target')).toBe('_blank');
    expect(wrapper.find('.copy').exists()).toBe(true);
    expect(wrapper.find('.link-revoked').exists()).toBe(false);
  });

  it('标题/摘要留空:createShare 不带空串字段(后端 blankToNull 语义)', async () => {
    const { wrapper } = await mountSetup('?sessionId=5');
    await wrapper.find('.f-title').setValue('   ');
    await wrapper.find('.sum-go').trigger('click');
    await flushPromises();
    const payload = mockedCreate.mock.calls[0]?.[0];
    expect(payload?.title).toBeUndefined();
    expect(payload?.summary).toBeUndefined();
  });

  it('busy 防双击:生成期间再点不重复提交', async () => {
    const { wrapper } = await mountSetup('?sessionId=5');
    let release: (v: { token: string; url: string }) => void = () => {};
    mockedCreate.mockReturnValueOnce(new Promise((res) => { release = res; }));

    await wrapper.find('.sum-go').trigger('click');
    await wrapper.find('.sum-go').trigger('click');
    release({ token: 'tok9abc', url: '/s/tok9abc' });
    await flushPromises();
    expect(mockedCreate).toHaveBeenCalledTimes(1);
  });

  it('全部取消勾选:生成按钮禁用并短路(后端空集 400 前置拦截)', async () => {
    const { wrapper } = await mountSetup('?sessionId=5');
    for (const ck of wrapper.findAll('.ck')) {
      await ck.setValue(false);
    }
    expect(wrapper.find('.sel-count').text()).toContain('已选 0 个节点');
    expect(wrapper.find('.sum-go').attributes('disabled')).toBeDefined();
    await wrapper.find('.sum-go').trigger('click');
    expect(mockedCreate).not.toHaveBeenCalled();
  });

  it('复制链接:clipboard.writeText(fullUrl),按钮反馈「已复制」;失败回落 toast 提示手动复制', async () => {
    const writeText = vi.fn<(text: string) => Promise<void>>().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const { wrapper } = await mountSetup('?sessionId=5');
    await wrapper.find('.sum-go').trigger('click');
    await flushPromises();

    await wrapper.find('.copy').trigger('click');
    await flushPromises();
    expect(writeText).toHaveBeenCalledWith('http://localhost:3000/s/tok9abc');
    expect(wrapper.find('.copy').text()).toBe('已复制');

    // 失败回落:clipboard 抛错 → toast 手动复制
    writeText.mockRejectedValueOnce(new Error('denied'));
    await wrapper.find('.copy').trigger('click');
    await flushPromises();
    expect(showToast).toHaveBeenCalledWith('复制失败,请长按链接手动复制');
  });

  it('撤销分享:revokeShare(token) 调用,链接标记「已撤销」,复制/打开入口隐藏', async () => {
    const { wrapper } = await mountSetup('?sessionId=5');
    await wrapper.find('.sum-go').trigger('click');
    await flushPromises();

    await wrapper.find('.revoke').trigger('click');
    await flushPromises();
    expect(mockedRevoke).toHaveBeenCalledWith('tok9abc');
    expect(wrapper.find('.link-revoked').text()).toContain('已撤销');
    expect(wrapper.find('.link').exists()).toBe(false);
    expect(wrapper.find('.copy').exists()).toBe(false);
    expect(wrapper.find('.open').exists()).toBe(false);
    expect(wrapper.find('.revoke').exists()).toBe(false);
  });

  it('生成失败(400 勾选越界):toast 错误信息,不进入已生成态', async () => {
    mockedCreate.mockRejectedValue(new ApiError(400, '节点不属于该会话'));
    const { wrapper } = await mountSetup('?sessionId=5');
    await wrapper.find('.sum-go').trigger('click');
    await flushPromises();
    expect(showToast).toHaveBeenCalledWith('节点不属于该会话');
    expect(wrapper.find('.link').exists()).toBe(false);
  });

  it('query 缺 sessionId:重定向回 /path,不拉树', async () => {
    const { wrapper, local } = await mountSetup('');
    await flushPromises();
    expect(local.currentRoute.value.path).toBe('/path');
    expect(mockedTree).not.toHaveBeenCalled();
    expect(wrapper.find('.branch').exists()).toBe(false);
  });

  it('树加载失败(403 非属主):错误空态,不渲染勾选区', async () => {
    mockedTree.mockRejectedValue(new ApiError(403, '无权访问该会话'));
    const { wrapper } = await mountSetup('?sessionId=5');
    expect(wrapper.find('.load-err').exists()).toBe(true);
    expect(wrapper.find('.load-err').text()).toContain('无权访问该会话');
    expect(wrapper.find('.sum-go').exists()).toBe(false);
  });
});
