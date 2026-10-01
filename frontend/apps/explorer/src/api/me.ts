/**
 * 个人空间 API 客户端(FR-U05 配额页):GET /api/me/quota → 今日智能服务配额
 * (used/limit/remaining/resetAt;resetAt 为次日零点,ISO-8601 串,由前端格式化)。
 * FR-U02 个人空间其余数据复用既有客户端,不在此重复封装:
 * 收藏 api/favorites.ts(listFavorites)、我的入口 api/entries.ts(fetchMyEntries)、
 * 路径总数 api/sessions.ts(fetchMySessions,size=1 只取 total)。
 */
import { http } from './http';

/** GET /api/me/quota 响应 data(= QuotaController.QuotaView 同形) */
export interface QuotaView {
  /** 今日已用次数 */
  used: number;
  /** 每日上限(默认 30) */
  limit: number;
  /** 剩余次数 = limit - used */
  remaining: number;
  /** 重置时间(次日零点,ISO-8601,如 2026-10-01T00:00:00+08:00) */
  resetAt: string;
}

/** 我的今日配额(04 §8.6:进度条主色,用尽变警示) */
export function fetchMyQuota(): Promise<QuotaView> {
  return http.get<QuotaView>('/me/quota');
}
