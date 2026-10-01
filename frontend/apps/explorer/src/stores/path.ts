import { defineStore } from 'pinia';
import { ApiError } from '../api/http';
import { addNode, fetchSessionTree, updateExplainLevel } from '../api/sessions';
import type { AddNodePayload, PathNode, SessionTree } from '../api/sessions';

/**
 * 探索路径树 store(FR-E03/E04/E05):扁平 nodes(后端 visited_at 升序)+ childrenMap
 * (parentId → 子节点数组,根节点键为 null)双轨 —— 前者供计数/查节点,后者供 PathTree 递归渲染。
 * currentNodeId=「当前节点」:载入时为最近访问节点,点历史节点后为所选节点(纯前端游标,
 * 服务端持久化以"最新节点"为准,当前游标只影响继续探索的挂载点)。
 */
interface PathState {
  sessionId: number | null;
  theme: string;
  goal: string;
  explainLevel: string;
  status: string;
  /** 完整树扁平数组(visited_at 升序) */
  nodes: PathNode[];
  /** parentId → 子节点数组(根键 null);loadTree 重建、addNodeAt 增量维护 */
  childrenMap: Map<number | null, PathNode[]>;
  currentNodeId: number | null;
  loading: boolean;
  /** 树不可进入(404 不存在/403 非属主/网络失败):视图渲染错误空态而非空白 */
  missing: boolean;
  errorMsg: string;
}

/** visited_at 升序遍历建 childrenMap,同父下天然保持时间序 */
function buildChildrenMap(nodes: PathNode[]): Map<number | null, PathNode[]> {
  const map = new Map<number | null, PathNode[]>();
  for (const n of nodes) {
    const list = map.get(n.parentNodeId);
    if (list) list.push(n);
    else map.set(n.parentNodeId, [n]);
  }
  return map;
}

export const usePathStore = defineStore('path', {
  state: (): PathState => ({
    sessionId: null,
    theme: '',
    goal: '',
    explainLevel: 'SIMPLE',
    status: '',
    nodes: [],
    childrenMap: new Map(),
    currentNodeId: null,
    loading: false,
    missing: false,
    errorMsg: ''
  }),

  getters: {
    /** 面包屑标题:最新节点卡题 → goal → 「新探索」(与后端 titleOf 同规则) */
    title(state): string {
      const last = state.nodes[state.nodes.length - 1];
      return last?.cardTitle || state.goal || '新探索';
    },
    currentNode(state): PathNode | null {
      return state.nodes.find((n) => n.nodeId === state.currentNodeId) ?? null;
    },
    /** 分叉点集合(≥2 子):挂「分支」微标 */
    branchIds(state): Set<number> {
      const ids = new Set<number>();
      state.childrenMap.forEach((children, parent) => {
        if (parent !== null && children.length >= 2) ids.add(parent);
      });
      return ids;
    }
  },

  actions: {
    /** 载入会话完整树;失败(404/403/网络)置 missing 态,由视图渲染错误空态 */
    async loadTree(id: number): Promise<void> {
      this.loading = true;
      this.missing = false;
      this.errorMsg = '';
      try {
        this.applyTree(await fetchSessionTree(id));
      } catch (e) {
        this.missing = true;
        this.errorMsg = e instanceof ApiError ? e.message : '加载失败,请稍后重试';
      } finally {
        this.loading = false;
      }
    },

    /** 树数据落库 + 游标定位最近访问节点 */
    applyTree(tree: SessionTree): void {
      this.sessionId = tree.sessionId;
      this.theme = tree.theme;
      this.goal = tree.goal;
      this.explainLevel = tree.explainLevel;
      this.status = tree.status;
      this.nodes = tree.nodes;
      this.childrenMap = buildChildrenMap(tree.nodes);
      this.currentNodeId = tree.nodes.length ? tree.nodes[tree.nodes.length - 1]!.nodeId : null;
    },

    /** 回到此节点:只接受树内既有节点(树外 id 不动游标) */
    setCurrent(id: number): void {
      if (this.nodes.some((n) => n.nodeId === id)) this.currentNodeId = id;
    },

    /**
     * 在某节点下继续探索(FR-E05;parentId null=挂根):调 API → push 扁平数组 +
     * 增量更新 childrenMap → 新节点成为当前。API 错误原样抛出由视图 toast。
     */
    async addNodeAt(parentId: number | null, payload: AddNodePayload = {}): Promise<PathNode> {
      if (this.sessionId == null) throw new Error('会话尚未加载');
      const node = await addNode(this.sessionId, { ...payload, parentNodeId: parentId ?? undefined });
      this.nodes.push(node);
      const list = this.childrenMap.get(node.parentNodeId);
      if (list) list.push(node);
      else this.childrenMap.set(node.parentNodeId, [node]);
      this.currentNodeId = node.nodeId;
      return node;
    },

    /** FR-E10 讲解度:成功后以响应回填;失败原样抛出由视图 toast,本地档位不变 */
    async patchExplainLevel(level: 'SIMPLE' | 'DEEP' | 'CHILD'): Promise<void> {
      if (this.sessionId == null) throw new Error('会话尚未加载');
      const resp = await updateExplainLevel(this.sessionId, level);
      this.explainLevel = resp.explainLevel;
    }
  }
});
