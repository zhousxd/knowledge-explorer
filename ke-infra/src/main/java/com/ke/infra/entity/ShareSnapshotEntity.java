package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * share_snapshot 表映射（V1__core_schema.sql）：分享快照（FR-H03：快照不可变 JSON）。
 * snapshot_json 为 JSONB 列，以 JSON 字符串读写（stringtype=unspecified 由 PG 推断类型）；
 * 生成后不 UPDATE/DELETE（撤销只翻 share.revoked，不抹快照——审计可回溯）。
 */
@TableName("share_snapshot")
public class ShareSnapshotEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shareId;
    private String snapshotJson;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getShareId() { return shareId; }
    public void setShareId(Long shareId) { this.shareId = shareId; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
