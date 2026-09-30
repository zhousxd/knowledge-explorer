package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * card 表映射（V1__core_schema.sql / V2__card_summary_and_immutability.sql）。
 * summary_text 为发布时由当前版本回填的搜索摘要列（FR-C02，pg_trgm GIN 索引覆盖 title 与它）；
 * 时间列 TIMESTAMPTZ 均有 DB 默认值 now()，插入时留 null 即可。
 */
@TableName("card")
public class CardEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String theme;
    private String templateType;
    private String title;
    private String summaryText;
    private String status;          // CardStatus.name()
    private Long currentVersionId;
    private Long maintainerId;
    private Integer sort;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public String getTemplateType() { return templateType; }
    public void setTemplateType(String templateType) { this.templateType = templateType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(Long currentVersionId) { this.currentVersionId = currentVersionId; }
    public Long getMaintainerId() { return maintainerId; }
    public void setMaintainerId(Long maintainerId) { this.maintainerId = maintainerId; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
