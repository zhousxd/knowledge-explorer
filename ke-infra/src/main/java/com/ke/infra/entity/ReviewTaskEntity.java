package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * review_task 表映射（V1__core_schema.sql，FR-O03 审核队列）：
 * submit/publish 等动作产生 PENDING 任务，编辑/运营在队列 approve（→APPROVED）或
 * reject（→REJECTED 带 notes）；object_type/object_id 指向被审核对象（CARD/ENTRY…）。
 * 时间列有 DB 默认值 now()，插入时留 null 即可；status 取值 PENDING/APPROVED/REJECTED。
 */
@TableName("review_task")
public class ReviewTaskEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String objectType;
    private Long objectId;
    private String action;
    private String status;
    private Long reviewerId;
    private String notes;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public Long getObjectId() { return objectId; }
    public void setObjectId(Long objectId) { this.objectId = objectId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
