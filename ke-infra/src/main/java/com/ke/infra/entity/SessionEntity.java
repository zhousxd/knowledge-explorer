package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * exploration_session 表映射（V1__core_schema.sql）：探索会话（FR-E01/E03）。
 * explain_level 取 SIMPLE/DEEP/CHILD（FR-E10 会话内记忆）；status 取 ACTIVE/ARCHIVED。
 * created_at/updated_at 均有 DB 默认值 now()；updated_at 按契约由应用层在
 * 创建/追节点/改讲解度时刷新（无 DB 触发器），latest 与列表按 updated_at DESC 排序。
 */
@TableName("exploration_session")
public class SessionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String theme;
    private String goal;
    private String explainLevel;
    private String status;
    private Long originShareId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public String getExplainLevel() { return explainLevel; }
    public void setExplainLevel(String explainLevel) { this.explainLevel = explainLevel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getOriginShareId() { return originShareId; }
    public void setOriginShareId(Long originShareId) { this.originShareId = originShareId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
