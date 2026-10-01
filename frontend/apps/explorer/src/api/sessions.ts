/**
 * 探索会话 API 客户端。
 * 端点 GET /api/sessions/latest 由 Phase 4 Task 16 交付:当前调用必然 404,
 * 客户端把 404/无数据归一为 null,调用方据以隐藏「继续探索卡」(FR-E01 断点续探入口)。
 */
import { ApiError, http } from './http';

/** 最近一次探索会话摘要(字段以 Phase 4 冻结契约为准,届时由 openapi 生成类型替换) */
export interface ResumeSession {
  /** 会话标题(最近节点所属卡片/主题) */
  title: string;
  /** 暂存说明,如「昨天暂存」 */
  progress: string;
  /** 已探索节点数 */
  nodes: number;
  /** 分支数 */
  branches: number;
}

/** 拉取当前用户最近一次探索会话;404(端点未交付/无会话)归一为 null */
export async function fetchLatestSession(): Promise<ResumeSession | null> {
  try {
    return await http.get<ResumeSession | null>('/sessions/latest');
  } catch (e) {
    if (e instanceof ApiError && e.code === 404) return null;
    throw e;
  }
}
