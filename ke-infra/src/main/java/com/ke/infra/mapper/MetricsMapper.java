package com.ke.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 6 指标聚合 SQL（FR-O05 / 01 §5，Task 33；只读标量，GET /api/wb/metrics 一次六键）。
 * 决策：service 内聚合 SQL，不建视图（视图仅多一层命名，无复用方）。
 * 窗口统一「最近 7 天」（created_at > now() - interval '7 days'）；分母为 0 → NULLIF → null
 * （语义「无数据」，前端显示 -，不做 0 化——0 会把「没发生」与「发生率为零」混为一谈）。
 */
@Mapper
public interface MetricsMapper {

    /**
     * ① 有效深入率 = node_visit 中 isNewKnowledge=true 占比。
     * 埋点处 SessionService.addNode（与 path_node.is_new_knowledge 同则写入事件）。
     */
    @Select("""
            SELECT count(*) FILTER (WHERE payload->>'isNewKnowledge' = 'true') * 1.0
                 / NULLIF(count(*), 0)
            FROM analytics_event
            WHERE event_type = 'node_visit' AND created_at > now() - interval '7 days'
            """)
    Double selectDeepenRate();

    /**
     * ② 成果保存率 = artifact_save 事件数 / 有 DONE 服务的 distinct 会话数
     * （artifact_save 在 SummaryService DONE 落，即「整理成果被保存」；service_run DONE 且
     * payload.sessionId 非空的会话为分母——无会话试运行 run 不落 artifact，不入分母）。
     */
    @Select("""
            SELECT (SELECT count(*) FROM analytics_event
                    WHERE event_type = 'artifact_save' AND created_at > now() - interval '7 days') * 1.0
                 / NULLIF((SELECT count(DISTINCT payload->>'sessionId') FROM analytics_event
                           WHERE event_type = 'service_run' AND payload->>'status' = 'DONE'
                             AND payload->>'sessionId' IS NOT NULL
                             AND created_at > now() - interval '7 days'), 0)
            """)
    Double selectArtifactSaveRate();

    /** ③ 分享接续率 = share_continue / share_view（接续按登录态、浏览含匿名，口径见 01 §5） */
    @Select("""
            SELECT (SELECT count(*) FROM analytics_event
                    WHERE event_type = 'share_continue' AND created_at > now() - interval '7 days') * 1.0
                 / NULLIF((SELECT count(*) FROM analytics_event
                           WHERE event_type = 'share_view' AND created_at > now() - interval '7 days'), 0)
            """)
    Double selectShareContinueRate();

    /**
     * ④ 入口成功率（口径近似，Task 33 授权钉）：入口试运行 = 无会话 run（sessionId 与 nodeId 均空，
     * P5-18 由 EntryMutationService.test 提交），本指标 = 无会话 run 中 DONE 占比。
     * 近似性：agent_run 无 run↔entry 关联列（P5-18 遗留），凡「无会话试运行形态」的 run 一并计入，
     * 与严格的「entry 提交后 DONE 比例」可能有偏差；run↔entry 关联落地后应换算为精确口径。
     */
    @Select("""
            SELECT count(*) FILTER (WHERE status = 'DONE') * 1.0 / NULLIF(count(*), 0)
            FROM agent_run
            WHERE session_id IS NULL AND node_id IS NULL
              AND created_at > now() - interval '7 days'
            """)
    Double selectEntrySuccessRate();

    /**
     * ⑤ 来源完整率 = 带非空 artifact_ids 的 DONE run / 全部 DONE run
     * （artifact_ids 为 JSONB 数组；讲解/整理 DONE 均回写，无会话 run 回写 '[]'）。
     */
    @Select("""
            SELECT count(*) FILTER (WHERE artifact_ids IS NOT NULL AND artifact_ids <> '[]'::jsonb) * 1.0
                 / NULLIF(count(*), 0)
            FROM agent_run
            WHERE status = 'DONE' AND created_at > now() - interval '7 days'
            """)
    Double selectSourceCompleteRate();

    /** ⑥-a 服务时延 = DONE run 的 avg(latency_ms)（FR-S13 计量 MVP 口径） */
    @Select("""
            SELECT avg(latency_ms) FROM agent_run
            WHERE status = 'DONE' AND created_at > now() - interval '7 days'
            """)
    Double selectAvgLatencyMs();

    /** ⑥-b 服务成本 = DONE run 的 avg(cost)；MVP 网关未采 tokens/cost（二期），恒 null */
    @Select("""
            SELECT avg(cost) FROM agent_run
            WHERE status = 'DONE' AND created_at > now() - interval '7 days'
            """)
    Double selectAvgCost();
}
