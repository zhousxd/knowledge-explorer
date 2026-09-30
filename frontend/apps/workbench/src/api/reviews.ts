/**
 * 工作台审核 API 客户端(FR-O03 界面):待审队列(卡片/入口)、通过并发布、驳回(意见必填)。
 * 队列为 offset 分页(page 从 1 起,size ≤100 默认 20),items 带 summary/precheck/contentPreview。
 */
import { http } from './http';
import type { ReviewItem, ReviewListResp, ReviewObjectType, ReviewTaskStatus } from './types';

export interface ListReviewsParams {
  /** 状态筛选,后端缺省 PENDING */
  status?: ReviewTaskStatus;
  /** 队列对象类型 */
  objectType?: ReviewObjectType;
  /** 页码,从 1 起 */
  page?: number;
  /** 每页条数,≤ 100 默认 20;徽标计数查询用 size=1 只取 total */
  size?: number;
}

/** GET /api/wb/reviews:队列查询(offset 分页) */
export function listReviews(params: ListReviewsParams = {}): Promise<ReviewListResp> {
  const search = new URLSearchParams();
  if (params.status) search.set('status', params.status);
  if (params.objectType) search.set('objectType', params.objectType);
  search.set('page', String(params.page ?? 1));
  search.set('size', String(params.size ?? 20));
  return http.get<ReviewListResp>(`/wb/reviews?${search.toString()}`);
}

/** POST /api/wb/reviews/{id}/approve:通过并发布(一击,不填意见——保持单步操作) */
export function approveReview(id: number): Promise<ReviewItem> {
  return http.post<ReviewItem>(`/wb/reviews/${id}/approve`, {});
}

/** POST /api/wb/reviews/{id}/reject:驳回,意见必填(后端 @NotBlank 兜底 400) */
export function rejectReview(id: number, notes: string): Promise<ReviewItem> {
  return http.post<ReviewItem>(`/wb/reviews/${id}/reject`, { notes });
}
