/**
 * 手写 TS 类型 —— 与后端 Phase 1 冻结契约同步;
 * gen:api 产物接入后由生成类型替换。
 */

/** 后端统一响应信封,code=0 表示成功,HTTP 状态与错误语义一致(401 含信封体) */
export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  traceId: string;
  data: T;
}

/** POST /api/auth/login 与 /api/auth/sms/login 响应 data */
export interface LoginResp {
  accessToken: string;
  refreshToken: string;
}

/** 用户角色(Phase 1 冻结取值;探索端当前只会拿到 EXPLORER) */
export type UserRole = 'EXPLORER' | 'CREATOR' | 'EDITOR' | 'OPERATOR';

/** GET /api/me 响应 data */
export interface MeResp {
  id: number;
  nickname: string;
  role: UserRole;
}
