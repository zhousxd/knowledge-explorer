package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * citation 表映射（V1__core_schema.sql，FR-O02）：统一引用结构 = 知识资产 + 定位器 + 原文摘录。
 * locator 为 JSONB 列（JSON 字符串读写，stringtype=unspecified）；
 * object_type ∈ {card_version, agent_run}（Phase 1 用 card_version，agent_run 为 Phase 5 预留）；
 * created_at 有 DB 默认值 now()，插入时留 null 即可。
 */
@TableName("citation")
public class CitationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long assetId;
    private String locator;      // JSONB 字符串，可空
    private String quote;        // 原文摘录，可空
    private String objectType;   // card_version / agent_run
    private Long objectId;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public String getLocator() { return locator; }
    public void setLocator(String locator) { this.locator = locator; }
    public String getQuote() { return quote; }
    public void setQuote(String quote) { this.quote = quote; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public Long getObjectId() { return objectId; }
    public void setObjectId(Long objectId) { this.objectId = objectId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
