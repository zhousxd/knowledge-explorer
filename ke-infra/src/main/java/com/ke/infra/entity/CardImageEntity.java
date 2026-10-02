package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * card_image 表映射（V7__card_image.sql）：卡片配图元数据。
 * storage_key 是 ke.upload.dir 下的相对路径（{yyyy}/{MM}/{uuid}.{ext}），不含用户可控字符；
 * original_name 仅留档展示，不参与存储寻址。created_at 有 DB 默认值 now()，插入时留 null。
 */
@TableName("card_image")
public class CardImageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String storageKey;
    private String originalName;
    private String contentType;
    private Long sizeBytes;
    private Long createdBy;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
