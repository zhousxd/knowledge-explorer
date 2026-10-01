import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/http';
import { addNode, fetchSessionTree, updateExplainLevel } from '../api/sessions';
import type { PathNode, SessionTree } from '../api/sessions';
import { usePathStore } from '../stores/path';

vi.mock('../api/sessions', () => ({
  fetchSessionTree: vi.fn(),
  addNode: vi.fn(),
  updateExplainLevel: vi.fn(),
  fetchLatestSession: vi.fn(),
  fetchMySessions: vi.fn(),
  createSession: vi.fn()
}));
const mockedTree = vi.mocked(fetchSessionTree);
const mockedAdd = vi.mocked(addNode);
const mockedLevel = vi.mocked(updateExplainLevel);

let seq = 0;
/** 造树节点:未给字段按安全缺省补齐(nodeId 自增防撞) */
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

function treeOf(nodes: PathNode[]): SessionTree {
  return {
    sessionId: 9, theme: 'academy', goal: '读懂岳麓书院', explainLevel: 'DEEP', status: 'ACTIVE',
    createdAt: '2026-09-28T09:00:00Z', updatedAt: '2026-09-30T10:00:00Z', nodes
  };
}

/** 链 3 节点 + 1 分支:1→2→3 主链,4 挂在 2 下 → 节点 2 为分叉点 */
const BRANCH_TREE = (): PathNode[] => [
  node({ nodeId: 1, cardTitle: '岳麓书院：从选址到人物', isNewKnowledge: true }),
  node({ nodeId: 2, parentNodeId: 1, cardTitle: '为什么建在这里', isNewKnowledge: true }),
  node({ nodeId: 3, parentNodeId: 2, questionText: '朱张会讲是谁主持的？' }),
  node({ nodeId: 4, parentNodeId: 2, cardTitle: '书院与山寺的关系', isNewKnowledge: true })
];

describe('path store(路径树)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    seq = 0;
  });

  it('loadTree 构建 childrenMap(根=null 键)并定位当前节点/元数据', async () => {
    mockedTree.mockResolvedValue(treeOf([
      node({ nodeId: 1, cardTitle: '根卡' }),
      node({ nodeId: 2, parentNodeId: 1, cardTitle: '二层卡' }),
      node({ nodeId: 3, parentNodeId: 2, cardTitle: '三层卡' })
    ]));

    const store = usePathStore();
    await store.loadTree(9);

    expect(mockedTree).toHaveBeenCalledWith(9);
    expect(store.sessionId).toBe(9);
    expect(store.explainLevel).toBe('DEEP');
    expect([...store.childrenMap.get(null)!].map((n) => n.nodeId)).toEqual([1]);
    expect(store.childrenMap.get(1)!.map((n) => n.nodeId)).toEqual([2]);
    expect(store.childrenMap.get(2)!.map((n) => n.nodeId)).toEqual([3]);
    // 当前节点=最近访问(visited_at 升序末位)
    expect(store.currentNodeId).toBe(3);
    // 面包屑标题=最新节点卡题(与后端 titleOf 同规则)
    expect(store.title).toBe('三层卡');
  });

  it('分支 fixture:分叉点(≥2 子)进 branchIds,链中节点不进', async () => {
    mockedTree.mockResolvedValue(treeOf(BRANCH_TREE()));

    const store = usePathStore();
    await store.loadTree(9);

    expect(store.branchIds.has(2)).toBe(true);
    expect(store.branchIds.has(1)).toBe(false);
    expect(store.branchIds.size).toBe(1);
  });

  it('addNodeAt 挂历史节点 → childrenMap 更新且新节点成为当前;挂根(null)同理', async () => {
    mockedTree.mockResolvedValue(treeOf(BRANCH_TREE()));
    const store = usePathStore();
    await store.loadTree(9);

    mockedAdd.mockResolvedValue(node({ nodeId: 5, parentNodeId: 2, cardTitle: '新分支卡' }));
    const created = await store.addNodeAt(2, { cardVersionId: 66 });

    expect(mockedAdd).toHaveBeenCalledWith(9, { cardVersionId: 66, parentNodeId: 2 });
    expect(store.childrenMap.get(2)!.map((n) => n.nodeId)).toEqual([3, 4, 5]);
    expect(store.nodes.at(-1)?.nodeId).toBe(5);
    expect(store.currentNodeId).toBe(created.nodeId);
    // 分叉点仍被识别(2 现有 3 子)
    expect(store.branchIds.has(2)).toBe(true);

    // 挂根:parentNodeId=null → childrenMap 根键追加
    mockedAdd.mockResolvedValue(node({ nodeId: 6, cardTitle: '根上新卡' }));
    await store.addNodeAt(null, { cardVersionId: 77 });
    expect(mockedAdd).toHaveBeenLastCalledWith(9, { cardVersionId: 77, parentNodeId: undefined });
    expect(store.childrenMap.get(null)!.map((n) => n.nodeId)).toEqual([1, 6]);
    expect(store.currentNodeId).toBe(6);
  });

  it('patchExplainLevel 成功回填档位;失败原样抛出且本地档位不变', async () => {
    mockedTree.mockResolvedValue(treeOf(BRANCH_TREE()));
    const store = usePathStore();
    await store.loadTree(9);

    mockedLevel.mockResolvedValue({ explainLevel: 'CHILD' });
    await store.patchExplainLevel('CHILD');
    expect(mockedLevel).toHaveBeenCalledWith(9, 'CHILD');
    expect(store.explainLevel).toBe('CHILD');

    mockedLevel.mockRejectedValueOnce(new ApiError(400, '讲解度仅支持 SIMPLE/DEEP/CHILD'));
    await expect(store.patchExplainLevel('SIMPLE')).rejects.toThrow('讲解度仅支持 SIMPLE/DEEP/CHILD');
    expect(store.explainLevel).toBe('CHILD');
  });

  it('loadTree 404/403 → missing 空态与错误消息;setCurrent 只接受树内节点', async () => {
    mockedTree.mockRejectedValue(new ApiError(404, '会话不存在'));

    const store = usePathStore();
    await store.loadTree(404);

    expect(store.missing).toBe(true);
    expect(store.errorMsg).toBe('会话不存在');
    expect(store.sessionId).toBeNull();

    // setCurrent 守卫:未加载/树外节点不动当前指针
    store.setCurrent(999);
    expect(store.currentNodeId).toBeNull();
    mockedTree.mockResolvedValue(treeOf(BRANCH_TREE()));
    await store.loadTree(9);
    store.setCurrent(1);
    expect(store.currentNodeId).toBe(1);
    store.setCurrent(999);
    expect(store.currentNodeId).toBe(1);
  });
});
