package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * artifact 表映射（V1__core_schema.sql，FR-S03）：成果快照，content_json 为不可变 JSON。
 * content_json / cited_run_ids 为 JSONB 列，此处以 JSON 字符串读写（stringtype=unspecified）；
 * session_id 非空约束——讲解流水线仅在 run 有会话时落 artifact；created_at 有 DB 默认值。
 */
@TableName("artifact")
public class ArtifactEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String type;         // EXPLAIN / SUMMARY / SHARE（Phase 5 先用 EXPLAIN）
    private String contentJson;  // JSONB 字符串
    private String citedRunIds;  // JSONB 字符串，可空
    private String status;       // DRAFT / FINAL（MVP 先落 DRAFT）
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public String getCitedRunIds() { return citedRunIds; }
    public void setCitedRunIds(String citedRunIds) { this.citedRunIds = citedRunIds; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
