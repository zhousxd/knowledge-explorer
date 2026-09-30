package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * entry 表映射（V1__core_schema.sql；V2 补 scope 注释）：
 * 卡片入口（FR-E02/C09/N07）。type 只允许 {@link com.ke.domain.enums.EntryType} 三类 name()；
 * relation_label 只允许四类关系词（com.ke.domain.entry.RelationType 中文标签）；
 * scope ∈ PRIVATE/PUBLIC；status ∈ ACTIVE/DISABLED；version 为乐观锁占位（默认 1）；
 * config_json 为 JSONB 原文（仅写路径/试运行需要，探索端响应不返回）。
 */
@TableName("entry")
public class EntryEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long cardId;
    private String name;
    private String type;            // EntryType.name()
    private String relationLabel;   // RelationType 中文标签（LINK_CARD 必填）
    private Long targetCardId;
    private String serviceType;
    private String configJson;
    private String scope;           // PRIVATE/PUBLIC
    private String status;          // ACTIVE/DISABLED
    private Integer version;
    private Long authorId;
    private Integer sort;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCardId() { return cardId; }
    public void setCardId(Long cardId) { this.cardId = cardId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getRelationLabel() { return relationLabel; }
    public void setRelationLabel(String relationLabel) { this.relationLabel = relationLabel; }
    public Long getTargetCardId() { return targetCardId; }
    public void setTargetCardId(Long targetCardId) { this.targetCardId = targetCardId; }
    public String getServiceType() { return serviceType; }
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
