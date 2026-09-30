/**
 * 手写 TS 类型 —— 与后端 Phase 1 冻结契约同步;
 * gen:api 产物接入后由生成类型替换。
 */

/** 后端 id 为 64 位 Long:JS number 仅安全表示 ≤ 2^53-1,MVP 量级内不触及精度边界(gen:api 接入时需复核精度策略) */

/** 后端统一响应信封,code=0 表示成功,HTTP 状态与错误语义一致(401 含信封体) */
export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  traceId: string;
  data: T;
}

/** POST /api/auth/login 响应 data */
export interface LoginResp {
  accessToken: string;
  refreshToken: string;
}

/** 工作台用户角色(Phase 1 冻结取值) */
export type UserRole = 'EXPLORER' | 'CREATOR' | 'EDITOR' | 'OPERATOR';

/** GET /api/me 响应 data */
export interface MeResp {
  id: number;
  nickname: string;
  role: UserRole;
}

/** 卡片状态(工作台管理) */
export type CardStatus = 'DRAFT' | 'PENDING' | 'PUBLISHED' | 'DISABLED';

/** 卡片模板类型(Phase 1 冻结四模板) */
export type CardTemplateType = 'TEXT' | 'COMPARE' | 'TIMELINE' | 'TASK';

/** GET /api/wb/cards 行 */
export interface CardListItem {
  id: number;
  theme: string;
  templateType: CardTemplateType;
  title: string;
  status: CardStatus;
  /** 当前版本号;current_version_id 未回填(草稿/待审)时为 null(序列化省略) */
  currentVersionNo: number | null;
  maintainerNickname: string | null;
  updatedAt: string;
}

/** offset 分页契约:page 从 1 起,size ≤ 100 默认 20,total 恒在 */
export interface PageResp<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

/** GET /api/wb/cards 响应 data */
export type CardListResp = PageResp<CardListItem>;

/** GET /api/wb/cards/{id}/versions 行(versionNo 倒序) */
export interface VersionItem {
  versionNo: number;
  createdByNickname: string | null;
  createdAt: string;
}
