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
  /** 维护者用户 id;前端按归属显隐 送审/停用(01 文档 RBAC) */
  maintainerId: number | null;
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

/** 来源引用(card_version.sources 元素;content 内 citations 为指向本数组的 1-based 索引) */
export interface SourceRef {
  /** 关联知识单元 id;null = 暂未挂接 */
  assetId: number | null;
  title: string;
  locator: string;
  license: string | null;
}

/** GET /api/wb/cards/{id} 响应 data(编辑器回填:content 内嵌对象 + sources) */
export interface WbCardDetail {
  id: number;
  theme: string;
  templateType: CardTemplateType;
  title: string;
  status: CardStatus;
  /** 模板内容对象(形状随 templateType,见四编辑器) */
  content: Record<string, unknown>;
  sources: SourceRef[];
}

/** PUT /api/wb/cards/{id}/content 请求体(存新版本) */
export interface SaveContentPayload {
  content: Record<string, unknown>;
  sources: SourceRef[];
}

/** 审核任务状态 */
export type ReviewTaskStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

/** 审核对象类型(入口队列 Phase 6 接入) */
export type ReviewObjectType = 'CARD' | 'ENTRY';

/** 机器预检(CARD:当前版本内容可解析 + 来源非空;ENTRY 暂为 null) */
export interface ReviewPrecheck {
  contentValid: boolean;
  hasSources: boolean;
}

/** GET /api/wb/reviews 行 */
export interface ReviewItem {
  id: number;
  objectType: ReviewObjectType;
  objectId: number;
  action: string;
  status: ReviewTaskStatus;
  createdAt: string;
  /** 「标题 · 模板 · 提交人」摘要;CARD 之外为 null */
  summary: string | null;
  precheck: ReviewPrecheck | null;
  /** 当前版本内容大意(截 100 字);内容不可解析或非 CARD 为 null */
  contentPreview: string | null;
}

/** GET /api/wb/reviews 响应 data */
export type ReviewListResp = PageResp<ReviewItem>;

/** 知识单元类型(CSV kind 列,后端冻结小写枚举) */
export type AssetKind = 'book' | 'article' | 'audio' | 'video';

/** GET /api/wb/assets 行(后端 AssetItem;sourceMeta/locator 为 JSON 对象) */
export interface AssetItem {
  id: number;
  kind: AssetKind;
  title: string;
  /** 来源元数据 JSON(author/press/journal 等),CSV 未给为 null */
  sourceMeta: Record<string, unknown> | null;
  /** 定位器 JSON(chapter/pages/t 等),CSV 未给为 null */
  locator: Record<string, unknown> | null;
  license: string | null;
  /** 授权到期日 yyyy-MM-dd,可空(空 = 未登记到期日) */
  licenseExpire: string | null;
  /** 后端计算:licenseExpire 非空且早于今日 */
  expired: boolean;
  /** 被引用次数(卡片版本/智能体运行) */
  citationCount: number;
}

/** GET /api/wb/assets 响应 data(后端 offset 分页 page 从 1 起,三端点分页契约已统一 1 基) */
export type AssetListResp = PageResp<AssetItem>;

/** POST /api/wb/assets/import 的失败行(部分成功语义:按物理行号回报) */
export interface AssetImportError {
  line: number;
  reason: string;
}

/** POST /api/wb/assets/import 响应 data */
export interface AssetImportResult {
  imported: number;
  skipped: number;
  errors: AssetImportError[];
}

/** GET /api/wb/assets/{id}/citations 行(统一引用 = 资产 + 定位器 + 原文摘录) */
export interface CitationItem {
  id: number;
  objectType: string;
  objectId: number;
  quote: string | null;
  locator: Record<string, unknown> | null;
}
