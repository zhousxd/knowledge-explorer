package com.ke.service.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ke.infra.mapper.MetricsMapper;
import org.springframework.stereotype.Service;

/**
 * 运营养护 6 指标（FR-O05 / 01 §5，Task 33）：GET /api/wb/metrics 一次返回六键，窗口「最近 7 天」，
 * 聚合 SQL 见 {@link MetricsMapper}（决策：service 内 6 条标量聚合，不建数据库视图）。
 * 分母为 0（窗口内无数据）→ 键值 null（语义「无数据」非 0）；前端工作台渲染属 Task 34。
 */
@Service
public class MetricsService {

    private final MetricsMapper metrics;

    public MetricsService(MetricsMapper metrics) {
        this.metrics = metrics;
    }

    /**
     * 六键视图（+ 成本，与 ⑥ 时延同源并列）：
     * ①deepenRate 有效深入率（node_visit 新知占比）；②artifactSaveRate 成果保存率
     * （artifact_save / 有 DONE 服务的会话）；③shareContinueRate 分享接续率（continue/view）；
     * ④entrySuccessRate 入口成功率（无会话试运行 run 中 DONE 占比，口径近似见 MetricsMapper 注）；
     * ⑤sourceCompleteRate 来源完整率（带 artifact 的 DONE run 占比）；⑥avgLatencyMs/avgCost 服务时延/成本
     * （cost 网关二期才采集，MVP 恒 null）。
     */
    // @JsonInclude(ALWAYS)：全局 jackson non_null 会把空数据的 null 键整个吞掉，
    // 指标契约要求「键恒在、值为 null」（前端据此显示 -，而非键缺失）
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MetricsView(Double deepenRate, Double artifactSaveRate, Double shareContinueRate,
                              Double entrySuccessRate, Double sourceCompleteRate,
                              Double avgLatencyMs, Double avgCost) {
    }

    public MetricsView view() {
        return new MetricsView(metrics.selectDeepenRate(), metrics.selectArtifactSaveRate(),
                metrics.selectShareContinueRate(), metrics.selectEntrySuccessRate(),
                metrics.selectSourceCompleteRate(), metrics.selectAvgLatencyMs(), metrics.selectAvgCost());
    }
}
