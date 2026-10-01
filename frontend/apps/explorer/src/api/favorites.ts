/**
 * 收藏 API 客户端(FR-C10):
 * POST/DELETE /cards/{id}/favorite(认证即可;非 PUBLISHED 卡 → 404;幂等)、
 * GET /me/favorites(我的收藏,created_at DESC,page 1 基、size ≤ 50 默认 20)。
 */
import { http } from './http';

/** 收藏/取消收藏响应 data */
export interface FavoriteResult {
  favorited: boolean;
}

/** 收藏列表行(favoritedAt 即 favorite.created_at,ISO 字符串) */
export interface FavoriteItem {
  cardId: number;
  title: string;
  theme: string;
  templateType: string;
  summaryText: string | null;
  favoritedAt: string;
}

/** 收藏列表分页(offset 分页,page 从 1 起;total 恒在) */
export interface FavoritePage {
  items: FavoriteItem[];
  total: number;
  page: number;
  size: number;
}

/** 收藏卡片(重复收藏幂等,返回原收藏) */
export function favorite(cardId: number): Promise<FavoriteResult> {
  return http.post<FavoriteResult>(`/cards/${cardId}/favorite`);
}

/** 取消收藏(未收藏也返回 favorited:false,不出错) */
export function unfavorite(cardId: number): Promise<FavoriteResult> {
  return http.del<FavoriteResult>(`/cards/${cardId}/favorite`);
}

/** 我的收藏列表(created_at DESC) */
export function listFavorites(page = 1, size = 20): Promise<FavoritePage> {
  return http.get<FavoritePage>(`/me/favorites?page=${page}&size=${size}`);
}
