import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { showToast } from 'vant';
import App from '../App.vue';
import router from '../router';
import { ApiError } from '../api/http';
import { addNode, fetchLatestSession, fetchSessionTree, updateExplainLevel } from '../api/sessions';
import type { PathNode, SessionTree } from '../api/sessions';

vi.mock('../api/sessions', () => ({
  fetchLatestSession: vi.fn(),
  fetchSessionTree: vi.fn(),
  addNode: vi.fn(),
  updateExplainLevel: vi.fn(),
  fetchMySessions: vi.fn(),
  createSession: vi.fn()
}));
vi.mock('vant', () => ({ showToast: vi.fn(), showConfirmDialog: vi.fn() }));
const mockedLatest = vi.mocked(fetchLatestSession);
const mockedTree = vi.mocked(fetchSessionTree);
const mockedAdd = vi.mocked(addNode);
const mockedLevel = vi.mocked(updateExplainLevel);

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

/** 链 3 节点 + 1 分支:1→2→3 主链,4 挂 2 下 → 当前=4,分叉点=2 */
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
    node({ nodeId: 4, parentNodeId: 2, cardTitle: '书院与山寺的关系', isNewKnowledge: true })
  ]
};

/** 经真实路由宿主进 /path(守卫要求登录态);单例路由先去 /login 强制重新导航 */
async function gotoPath(query = ''): Promise<ReturnType<typeof mount>> {
  localStorage.setItem('ke_ex_token', 'tok');
  const pinia = createPinia();
  setActivePinia(pinia);
  await router.push('/login');
  await router.push(`/path${query}`);
  const wrapper = mount(App, { global: { plugins: [pinia, router] } });
  await flushPromises();
  return wrapper;
}

describe('PathView(我的路径,FR-E03/E04/E05)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  it('无会话(latest 404→null):渲染空态引导去首页,不拉树', async () => {
    mockedLatest.mockResolvedValue(null);
    const wrapper = await gotoPath();

    expect(mockedLatest).toHaveBeenCalledTimes(1);
    expect(mockedTree).not.toHaveBeenCalled();
    expect(wrapper.find('.empty-ic').exists()).toBe(true);
    expect(wrapper.text()).toContain('还没有探索路径');
    expect(wrapper.text()).toContain('从首页任一张卡片开始');
    const btn = wrapper.findAll('.empty-btn').find((b) => b.text() === '去首页逛逛');
    expect(btn).toBeDefined();
    await btn!.trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/home');
  });

  it('?sessionId 直载树(不调 latest):4 节点行,分支 chip 在分叉点,当前 chip 在最近节点', async () => {
    mockedTree.mockResolvedValue(TREE);
    const wrapper = await gotoPath('?sessionId=5');

    expect(mockedLatest).not.toHaveBeenCalled();
    expect(mockedTree).toHaveBeenCalledWith(5);
    const rows = wrapper.findAll('.node');
    expect(rows).toHaveLength(4);
    // 面包屑:我的路径 · {最新节点卡题}
    expect(wrapper.find('.crumb').text()).toContain('我的路径');
    expect(wrapper.find('.crumb').text()).toContain('书院与山寺的关系');
    // 分支微标挂在 ≥2 子的分叉点
    const fork = rows.find((r) => r.text().includes('为什么建在这里'));
    expect(fork?.find('.chip.fork').text()).toBe('分支');
    expect(rows.find((r) => r.text().includes('从选址到人物'))?.find('.chip.fork').exists()).toBe(false);
    // 当前 chip 在最近访问节点
    const cur = rows.find((r) => r.classes().includes('cur'));
    expect(cur?.text()).toContain('书院与山寺的关系');
    expect(cur?.find('.chip.now').text()).toBe('当前');
  });

  it('点历史节点出浮条;纯确认(无跳转参数)只切当前不调 addNode', async () => {
    mockedTree.mockResolvedValue(TREE);
    const wrapper = await gotoPath('?sessionId=5');

    const root = wrapper.findAll('.node').find((r) => r.text().includes('从选址到人物'))!;
    await root.trigger('click');
    await flushPromises();
    const bar = wrapper.find('.resume-bar');
    expect(bar.exists()).toBe(true);
    expect(bar.text()).toContain('将在「岳麓书院：从选址到人物」下继续探索');

    await bar.find('.rb-go').trigger('click');
    await flushPromises();
    expect(mockedAdd).not.toHaveBeenCalled();
    expect(wrapper.find('.resume-bar').exists()).toBe(false);
    // 当前 chip 移到所点节点
    const cur = wrapper.findAll('.node').find((r) => r.classes().includes('cur'));
    expect(cur?.text()).toContain('从选址到人物');
  });

  it('带 cardVersionId 跳转进入:浮条确认调 addNode(父节点+卡版本),新节点成为当前', async () => {
    mockedTree.mockResolvedValue(TREE);
    mockedAdd.mockResolvedValue(node({ nodeId: 8, parentNodeId: 1, cardTitle: '新分支卡' }));
    const wrapper = await gotoPath('?sessionId=5&cardVersionId=66');

    const root = wrapper.findAll('.node').find((r) => r.text().includes('从选址到人物'))!;
    await root.trigger('click');
    await wrapper.find('.resume-bar .rb-go').trigger('click');
    await flushPromises();

    expect(mockedAdd).toHaveBeenCalledWith(5, { cardVersionId: 66, parentNodeId: 1 });
    // 新节点入树并成为当前;分叉点 childrenMap 增量更新(节点 1 现有 2 子 → 成分叉点)
    const rows = wrapper.findAll('.node');
    expect(rows).toHaveLength(5);
    const cur = rows.find((r) => r.classes().includes('cur'));
    expect(cur?.text()).toContain('新分支卡');
    expect(rows.find((r) => r.text().includes('从选址到人物'))?.find('.chip.fork').exists()).toBe(true);
  });

  it('档位 chip 点击调 PUT 并回填高亮;失败 toast 报错且档位不变', async () => {
    mockedTree.mockResolvedValue(TREE);
    mockedLevel.mockRejectedValueOnce(new ApiError(400, '讲解度仅支持 SIMPLE/DEEP/CHILD'));
    const wrapper = await gotoPath('?sessionId=5');

    const deep = wrapper.findAll('.lv').find((b) => b.text() === '深入')!;
    await deep.trigger('click');
    await flushPromises();
    expect(mockedLevel).toHaveBeenCalledWith(5, 'DEEP');
    expect(wrapper.findAll('.lv').find((b) => b.classes().includes('on'))?.text()).toBe('简明');
    expect(mockedShowToast().mock.calls.at(-1)?.[0]).toBe('讲解度仅支持 SIMPLE/DEEP/CHILD');

    mockedLevel.mockResolvedValue({ explainLevel: 'DEEP' });
    await deep.trigger('click');
    await flushPromises();
    expect(wrapper.findAll('.lv').find((b) => b.classes().includes('on'))?.text()).toBe('深入');
  });

  it('暂存=直接离开:toast 提示并回首页', async () => {
    mockedTree.mockResolvedValue(TREE);
    const wrapper = await gotoPath('?sessionId=5');

    const stash = wrapper.findAll('.pausebar .pb').find((b) => b.text().includes('暂存'))!;
    await stash.trigger('click');
    await flushPromises();

    expect(mockedShowToast().mock.calls.at(-1)?.[0]).toBe('已暂存,可随时回来');
    expect(router.currentRoute.value.path).toBe('/home');
  });
});

function mockedShowToast() {
  return vi.mocked(showToast);
}
