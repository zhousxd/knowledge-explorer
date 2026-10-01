package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * analytics_event 表映射（V6__analytics.sql，FR-O05 / Task 33）：埋点事件（一业务动作一行）。
 * payload 为 JSONB 列，此处以 JSON 字符串读写（与 agent_run.input_json 同策略，
 * 数据源 stringtype=unspecified 由 PG 推断类型）；时间列 TIMESTAMPTZ 有 DB 默认值 now()，
 * 插入时留 null 即可（指标窗口按 created_at 过滤）。
 */
@TableName("analytics_event")
public class AnalyticsEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String eventType;
    private String payload;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
