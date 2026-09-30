/**
 * 工作台卡片 API 客户端(FR-C07/C08 界面):
 * 列表(offset 分页 + 状态筛选 + 标题检索)、版本历史、送审、停用、新建。
 * 发布动作不在此接入——审核中心(Task 10)走 POST /publish。
 */
import { http } from './http';
import type { CardListResp, CardStatus, CardTemplateType, VersionItem } from './types';

export interface ListCardsParams {
  /** 状态筛选;空串 = 全部 */
  status?: CardStatus | '';
  /** 标题 ILIKE 关键词 */
  q?: string;
  /** 页码,从 1 起 */
  page?: number;
  /** 每页条数,≤ 100 默认 20 */
  size?: number;
}

/** GET /api/wb/cards:updated_at 倒序,非法 status 由后端 400 */
export async function listCards(params: ListCardsParams): Promise<CardListResp> {
  const search = new URLSearchParams();
  if (params.status) search.set('status', params.status);
  if (params.q) search.set('q', params.q);
  search.set('page', String(params.page ?? 1));
  search.set('size', String(params.size ?? 20));
  return http.get<CardListResp>(`/wb/cards?${search.toString()}`);
}

/** GET /api/wb/cards/{id}/versions:versionNo 倒序 */
export function getCardVersions(id: number): Promise<VersionItem[]> {
  return http.get<VersionItem[]>(`/wb/cards/${id}/versions`);
}

/** POST /api/wb/cards/{id}/submit:DRAFT → PENDING,并入审核队列 */
export function submitCard(id: number): Promise<{ cardId: number; status: string }> {
  return http.post(`/wb/cards/${id}/submit`);
}

/** POST /api/wb/cards/{id}/disable:下架(PUBLISHED → DISABLED) */
export function disableCard(id: number): Promise<{ cardId: number; status: string }> {
  return http.post(`/wb/cards/${id}/disable`);
}

export interface CreateCardPayload {
  theme: string;
  templateType: CardTemplateType;
  title: string;
  /** 首版内容对象(工作台新建用最小合法占位,后续在内容编辑器补全) */
  content: Record<string, unknown>;
}

/** POST /api/wb/cards:建卡 + 首版(DRAFT) */
export function createCard(payload: CreateCardPayload): Promise<{ cardId: number }> {
  return http.post('/wb/cards', payload);
}
