-- 埋点事件（FR-O05 / 01 §5，Task 33）：一次业务动作一行事件，6 指标（GET /api/wb/metrics）
-- 的聚合数据源。写入策略（Task 33 决策）：AnalyticsService 同步单行 INSERT（成本低、不丢事件），
-- 埋点自身异常吞掉记日志，不阻断业务。
CREATE TABLE analytics_event (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT,
    event_type VARCHAR(30) NOT NULL,
    payload    JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_analytics_type_time ON analytics_event (event_type, created_at);
