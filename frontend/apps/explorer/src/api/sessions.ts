/**
 * 探索会话 API 客户端 —— Phase 4 Task 16 冻结契约(以 SessionController/SessionService 为准)。
 * 全部端点恒需认证(匿名 401 由 http 层统一登出);latest 404(无会话)归一为 null(P3-13 冻结契约),
 * 调用方据以隐藏继续探索卡/显示路径空态。字段命名与后端 record 逐字对齐:
 * 树节点为 nodeId/parentNodeId(NodeView),时间戳均为 ISO-8601 串,由前端格式化。
 */
import { ApiError, http } from './http';

/** FR-E10 讲解度白名单(后端 SIMPLE/DEEP/CHILD,非法 400) */
export type ExplainLevel = 'SIMPLE' | 'DEEP' | 'CHILD';

/** GET /api/sessions/latest → ResumeSession(Phase 4 冻结形状;openQuestionCount 恒 0,Phase 5 接) */
export interface ResumeSession {
  sessionId: number;
  /** 会话标题(最新节点所访卡题 → goal → 「新探索」) */
  title: string;
  /** 最近访问时间(ISO),展示层格式化为「今天/昨天/N月N日探索」 */
  lastVisitedAt: string;
  nodeCount: number;
  branchCount: number;
  /** 未决疑问数(Phase 5 接,当前恒 0) */
  openQuestionCount: number;
}

/** GET /api/sessions 列表行(我的路径,updated_at DESC) */
export interface SessionSummaryItem {
  sessionId: number;
  theme: string;
  title: string;
  goal: string;
  nodeCount: number;
  branchCount: number;
  lastVisitedAt: string;
  status: string;
}

/** offset 分页信封(page 从 1 起、total 恒在),与收藏/工作台列表同形 */
export interface SessionPage {
  items: SessionSummaryItem[];
  total: number;
  page: number;
  size: number;
}

/** 路径树节点(后端 NodeView;cardTitle 由后端批量 join 带出,纯追问节点为 null) */
export interface PathNode {
  nodeId: number;
  /** 父节点 id;根为 null(树由 parent_node_id 自然成) */
  parentNodeId: number | null;
  cardVersionId: number | null;
  entryId: number | null;
  questionText: string | null;
  /** 同会话内该卡版本首次出现=新知识(01 A1 语义) */
  isNewKnowledge: boolean;
  visitedAt: string;
  cardTitle: string | null;
}

/** GET /api/sessions/{id} → 会话元数据 + 完整树(visited_at 升序;403 非属主/404 不存在/401 匿名) */
export interface SessionTree {
  sessionId: number;
  /** jackson non_null 对空值整键省略 → 可选;消费处走 || 兜底(goal→「新探索」) */
  theme?: string;
  goal?: string;
  explainLevel: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  nodes: PathNode[];
}

/** POST /api/sessions → 201 {sessionId} */
export interface CreatedSession {
  sessionId: number;
}

/** POST /api/sessions/{id}/nodes 请求体(均可选;分支=指定历史 parentNodeId) */
export interface AddNodePayload {
  cardVersionId?: number;
  entryId?: number;
  parentNodeId?: number;
  questionText?: string;
}

/** 创建会话(theme 收 shared THEMES 的 key:academy/cuisine/sound) */
export function createSession(theme: string, goal: string): Promise<CreatedSession> {
  return http.post<CreatedSession>('/sessions', { theme, goal });
}

/** 我的路径列表(offset 分页,page 从 1 起、size ≤ 50 默认 20) */
export function fetchMySessions(page = 1, size = 20): Promise<SessionPage> {
  return http.get<SessionPage>(`/sessions?page=${page}&size=${size}`);
}

/** 断点续探摘要;404(无会话)归一为 null,其余错误原样抛出 */
export async function fetchLatestSession(): Promise<ResumeSession | null> {
  try {
    return await http.get<ResumeSession>('/sessions/latest');
  } catch (e) {
    if (e instanceof ApiError && e.code === 404) return null;
    throw e;
  }
}

/**
 * wire 层归一(在进 store 之前,单一实现):后端 jackson default-property-inclusion: non_null
 * 会把 null 键整键省略 —— 根节点 parentNodeId 缺键成 undefined,而树渲染以 null 为根键,
 * 不归一则真实 API 下 /path 渲染空树。树接口与追节点响应同族,统一走此函数。
 */
function normalizeNode(n: PathNode): PathNode {
  return { ...n, parentNodeId: n.parentNodeId ?? null };
}

/** 会话 + 完整树(visited_at 升序);403/404 语义由调用方按 ApiError.code 处理 */
export async function fetchSessionTree(id: number): Promise<SessionTree> {
  const tree = await http.get<SessionTree>(`/sessions/${id}`);
  return { ...tree, nodes: tree.nodes.map(normalizeNode) };
}

/** 追加节点(parentNodeId 缺省=挂根;isNewKnowledge 由后端判定) */
export async function addNode(sessionId: number, payload: AddNodePayload): Promise<PathNode> {
  return normalizeNode(await http.post<PathNode>(`/sessions/${sessionId}/nodes`, payload));
}

/** FR-E10 讲解度(SIMPLE/DEEP/CHILD):成功响应回填 {explainLevel} */
export function updateExplainLevel(sessionId: number, level: ExplainLevel): Promise<{ explainLevel: string }> {
  return http.put<{ explainLevel: string }>(`/sessions/${sessionId}/explain-level`, { level });
}
