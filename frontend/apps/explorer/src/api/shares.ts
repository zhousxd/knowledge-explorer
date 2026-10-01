/**
 * 分享 API 客户端 —— P7-30/31 冻结契约(以 ShareController/SharePublicController 为准):
 * - POST /api/shares、DELETE /api/shares/{token} 走认证面 http 客户端(baseURL /api);
 * - GET /s/{token}、POST /s/{token}/continue 不在 /api 前缀下(SecurityConfig 仅对
 *   GET /s/* permitAll),由本模块 rawRequest 直连同路径:信封解析与错误归一与 http.ts
 *   同则,但**不接 401 全局登出回调**(接收者页匿名是常态,401 仅作错误文案上抛)。
 */
import { ApiError, http, TOKEN_KEY } from './http';
import type { ApiResponse } from './types';

/** 快照入口:最小展示面(name · relationLabel),不泄露 authorId/scope/status */
export interface ShareEntry {
  name: string;
  relationLabel: string;
}

/** 快照节点(后端 SnapshotJson.SnapshotNode 逐字对齐;removed=true 为占位行,note 是占位文案) */
export interface ShareSnapshotNode {
  title: string | null;
  cardVersionId: number | null;
  entries: ShareEntry[] | null;
  question: string | null;
  visitedAt: string | null;
  removed: boolean;
  note: string | null;
  nodeRef: number | null;
  parentNodeRef: number | null;
}

/** 快照体(接收者页渲染的唯一数据源,自足——分享可撤销、原会话可变) */
export interface ShareSnapshot {
  title: string | null;
  summary: string | null;
  nodes: ShareSnapshotNode[];
  generatedAt: string | null;
}

/** GET /s/{token} → 分享视图(continueNotice=FR-H07 静态差异提示,前端常驻提示条) */
export interface PublicShare {
  token: string;
  title: string | null;
  summary: string | null;
  snapshot: ShareSnapshot;
  createdAt: string | null;
  continueNotice: string;
}

/** POST /api/shares → 201 {token, url:/s/{token}}(url 为路径片段,前端拼 origin 成完整链接) */
export interface ShareCreated {
  token: string;
  url: string;
}

/** POST /s/{token}/continue → 接续副本 {sessionId, nodeCount}(Task 31) */
export interface ContinueResult {
  sessionId: number;
  nodeCount: number;
}

/** 创建分享载荷:objectType MVP 仅 'SESSION';nodeIds=勾选入快照的会话节点(空集 400) */
export interface CreateSharePayload {
  objectType: 'SESSION';
  objectId: number;
  nodeIds: number[];
  title?: string;
  summary?: string;
}

/** 创建分享(认证):勾选集越界/空集 400、非属主 403、会话不存在 404 */
export function createShare(payload: CreateSharePayload): Promise<ShareCreated> {
  return http.post<ShareCreated>('/shares', payload);
}

/** 撤销分享(认证,属主):幂等 200 {revoked:true};他人 403;不存在 404 */
export function revokeShare(token: string): Promise<{ revoked: boolean }> {
  return http.del<{ revoked: boolean }>(`/shares/${encodeURIComponent(token)}`);
}

/** /s/* 直连请求:信封解析与 http.ts 同则;401 不触发全局登出(见模块注释) */
async function rawRequest<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = body === undefined ? {} : { 'Content-Type': 'application/json' };
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) headers.Authorization = `Bearer ${token}`;
  let resp: Response;
  try {
    resp = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(-1, '网络异常');
  }
  let envelope: Partial<ApiResponse<T>> = {};
  try {
    envelope = (await resp.json()) as ApiResponse<T>;
  } catch {
    // 非 JSON 响应(如网关 5xx 页面)保留空信封,走下方兜底分支
  }
  if (envelope.code !== 0) {
    throw new ApiError(envelope.code ?? resp.status, envelope.message ?? '服务异常', envelope.traceId ?? '');
  }
  return envelope.data as T;
}

/** 免登录浏览(GET /s/{token},permitAll):撤销/不存在/快照缺失统一 404「分享不存在」不泄露存在性 */
export function fetchPublicShare(token: string): Promise<PublicShare> {
  return rawRequest<PublicShare>('GET', `/s/${encodeURIComponent(token)}`);
}

/** 接续副本(认证):按快照复制独立会话;匿名 401、撤销与不存在 404 */
export function continueShare(token: string): Promise<ContinueResult> {
  return rawRequest<ContinueResult>('POST', `/s/${encodeURIComponent(token)}/continue`, {});
}
