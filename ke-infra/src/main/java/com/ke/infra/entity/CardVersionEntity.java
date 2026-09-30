package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * card_version 表映射（V1__core_schema.sql / V2__card_summary_and_immutability.sql）：
 * 版本不可变（FR-C07/C08）——仅允许 INSERT，UPDATE/DELETE 由 DB 触发器
 * trg_card_version_immutable 拒绝，故只提供读字段，不做更新语义封装。
 * content_json / sources 为 JSONB 列，此处以 JSON 字符串读写；
 * created_at 有 DB 默认值 now()，插入时留 null 即可。
 */
@TableName("card_version")
public class CardVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long cardId;
    private Integer versionNo;
    private String contentJson;
    private String sources;
    private Long createdBy;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCardId() { return cardId; }
    public void setCardId(Long cardId) { this.cardId = cardId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public String getSources() { return sources; }
    public void setSources(String sources) { this.sources = sources; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
