/**
 * 运营养护指标 API 客户端(FR-O05 界面,P8-33 契约):GET /api/wb/metrics 一次返回七键,
 * 窗口「最近 7 天」,仅 EDITOR/OPERATOR(EXPLORER → 403 envelope,由 http 层抛 ApiError)。
 * 分母为 0(窗口内无数据)→ 键值 null(语义「无数据」非 0);后端 @JsonInclude(ALWAYS)
 * 保证键恒在,前端据 null 渲「暂无数据」灰态。
 */
import { http } from './http';

/** GET /api/wb/metrics 响应 data(= MetricsService.MetricsView;五率 + 时延/成本,值均可 null) */
export interface MetricsResp {
  /** ①有效深入率:node_visit 新知占比 */
  deepenRate: number | null;
  /** ②成果保存率:artifact_save / 有 DONE 服务的会话 */
  artifactSaveRate: number | null;
  /** ③分享接续率:continue / view */
  shareContinueRate: number | null;
  /** ④入口成功率:无会话试运行 run 中 DONE 占比 */
  entrySuccessRate: number | null;
  /** ⑤来源完整率:带 artifact 的 DONE run 占比 */
  sourceCompleteRate: number | null;
  /** ⑥服务时延(DONE run 平均,ms) */
  avgLatencyMs: number | null;
  /** 服务成本(¥/次;网关二期才采集,MVP 恒 null) */
  avgCost: number | null;
}

/** 我的看板六指标(五率 + 时延/成本) */
export function fetchMetrics(): Promise<MetricsResp> {
  return http.get<MetricsResp>('/wb/metrics');
}
