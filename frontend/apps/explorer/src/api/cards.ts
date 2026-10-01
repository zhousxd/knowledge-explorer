/**
 * 探索端公开卡片 API 客户端(FR-C01/C02):
 * 列表(keyset 游标分页 + 专题/关键词过滤)、单卡详情(仅 PUBLISHED,非 PUBLISHED/不存在 → 404)。
 * 单卡入口列表(P1-6,认证即可;匿名不调用,由视图层守卫)。
 * 404 统一归一为 null,调用方据以渲染「卡片不存在/已下架」空态。
 */
import { ApiError, http } from './http';

/** 模板类型(Phase 1 冻结四型;未知值由 CardRenderer 走兜底文案) */
export type CardTemplateType = 'TEXT' | 'COMPARE' | 'TIMELINE' | 'TASK';

/** 来源依据(响应数组顺序即角标 1-based 序号) */
export interface CardSourceRef {
  assetId?: number;
  title: string;
  locator: string;
  license?: string;
}

// ---------- 四模板 content 形状(与后端 ke-domain CardContent 记录同步,camelCase) ----------

export interface TextSection {
  h: string;
  body: string;
  /** 指向 sources 的 1-based 索引 */
  citations?: number[];
}

export interface TextRelated {
  cardId: number;
  relation: string;
  why: string;
  source?: number;
}

export interface TextContent {
  summary: string;
  sections: TextSection[];
  related?: TextRelated[];
}

export interface CompareContent {
  objects: string[];
  dimensions: string[];
  /** rows = dimensions.length,cols = objects.length */
  cells: string[][];
  citations?: number[];
}

export interface TimelineEvent {
  year: string;
  title: string;
  body?: string;
  cardId?: number;
  citations?: number[];
}

export interface TimelineContent {
  events: TimelineEvent[];
}

export interface TaskStep {
  place?: string;
  observe: string;
  minutes: number;
}

export interface TaskContent {
  goal: string;
  steps: TaskStep[];
  recordSchema: string[];
}

// ---------- 响应 ----------

/** GET /api/cards/{id} 响应 data(仅 PUBLISHED);favorited = 当前用户已收藏否(匿名 false,FR-C10) */
export interface CardDetail {
  id: number;
  theme: string;
  templateType: string;
  title: string;
  /** 当前展示版本的 card_version 表 PK —— 智能服务挂节点/提交 run(P5-21)与探索入口挂节点
   *  (A1③ 数据面)的必需入参;后端公开详情响应已带出该键(CardPublicController body 字段)。 */
  cardVersionId: number;
  versionNo: number;
  updatedAt: string;
  favorited: boolean;
  content: unknown;
  sources: CardSourceRef[];
}

/** GET /api/cards 列表行(summaryText 发布回填,DRAFT 期可能为 null) */
export interface PublicCardListItem {
  id: number;
  theme: string;
  templateType: string;
  title: string;
  summaryText: string | null;
  sort?: number;
  updatedAt?: string;
}

/** keyset 分页:nextCursor = null 表示没有更多 */
export interface PublicCardPage {
  items: PublicCardListItem[];
  nextCursor: string | null;
}

/** 入口行(GET /api/cards/{id}/entries,认证即可) */
export interface CardEntryItem {
  id: number;
  name: string;
  type: string;
  relationLabel: string | null;
  targetCardId: number | null;
  serviceType: string | null;
  scope: string;
  status: string;
  mine: boolean;
}

/** 后端固定切 5 个进 defaultEntries,其余进 folded */
export interface CardEntryGroup {
  cardId: number;
  defaultEntries: CardEntryItem[];
  folded: CardEntryItem[];
}

export interface ListPublicCardsParams {
  /** 专题 key(空 = 全部) */
  theme?: string;
  /** 标题 + 摘要 ILIKE 关键词 */
  q?: string;
  /** 上一页返回的 nextCursor */
  cursor?: string;
}

function toQuery(params: ListPublicCardsParams): string {
  const search = new URLSearchParams();
  if (params.theme) search.set('theme', params.theme);
  if (params.q) search.set('q', params.q);
  if (params.cursor) search.set('cursor', params.cursor);
  const qs = search.toString();
  return qs ? `?${qs}` : '';
}

/** GET /api/cards:已发布卡片列表(keyset 游标) */
export function listPublicCards(params: ListPublicCardsParams): Promise<PublicCardPage> {
  return http.get<PublicCardPage>(`/cards${toQuery(params)}`);
}

/** GET /api/cards/{id}:单卡详情;404(不存在/未发布/已下架)归一为 null */
export async function getCard(id: number): Promise<CardDetail | null> {
  try {
    return await http.get<CardDetail>(`/cards/${id}`);
  } catch (e) {
    if (e instanceof ApiError && e.code === 404) return null;
    throw e;
  }
}

/** GET /api/cards/{id}/entries:入口列表;卡不存在/未发布 → 404 归一为 null */
export async function fetchCardEntries(id: number): Promise<CardEntryGroup | null> {
  try {
    return await http.get<CardEntryGroup>(`/cards/${id}/entries`);
  } catch (e) {
    if (e instanceof ApiError && e.code === 404) return null;
    throw e;
  }
}
