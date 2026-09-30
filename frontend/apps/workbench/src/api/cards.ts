/**
 * 工作台卡片 API 客户端(FR-C07/C08 界面):
 * 列表(offset 分页 + 状态筛选 + 标题检索)、单卡读取(编辑器回填)、版本历史、
 * 送审、停用、新建、存新版本。
 * 发布动作不在此接入——审核中心(Task 10)走 POST /publish。
 */
import { http } from './http';
import type {
  CardListResp,
  CardStatus,
  CardTemplateType,
  SaveContentPayload,
  SourceRef,
  VersionItem,
  WbCardDetail
} from './types';

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
  /** 内容对象(新建页为编辑器组装的完整首版) */
  content: Record<string, unknown>;
  /** 首版来源(citations 为指向本数组的 1-based 索引);省略 = 无来源 */
  sources?: SourceRef[];
}

/** POST /api/wb/cards:建卡 + 首版(DRAFT) */
export function createCard(payload: CreateCardPayload): Promise<{ cardId: number }> {
  return http.post('/wb/cards', payload);
}

/** GET /api/wb/cards/{id}:单卡读取(content 内嵌对象 + sources,全状态可见,归属同写路径) */
export function getWbCard(id: number): Promise<WbCardDetail> {
  return http.get<WbCardDetail>(`/wb/cards/${id}`);
}

/** PUT /api/wb/cards/{id}/content:内容存为新版本(版本不可变,编辑 = 追加) */
export function saveCardContent(id: number, payload: SaveContentPayload): Promise<{ versionNo: number }> {
  return http.put(`/wb/cards/${id}/content`, payload);
}
