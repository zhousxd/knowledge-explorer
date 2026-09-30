package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * audit_log 表映射（V3__review_and_audit.sql，02 §9 / FR-O03）：
 * 由 {@link com.ke.service.review.AuditAspect} 在 @Audited 方法成功后自动写入，
 * 业务代码不直接操作本表。detail_json 为 JSONB 列，以 JSON 字符串读写；
 * actor_id / detail_json / created_at 可空（created_at 有 DB 默认值 now()）。
 */
@TableName("audit_log")
public class AuditLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long actorId;
    private String action;
    private String objectType;
    private Long objectId;
    private String detailJson;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public Long getObjectId() { return objectId; }
    public void setObjectId(Long objectId) { this.objectId = objectId; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
