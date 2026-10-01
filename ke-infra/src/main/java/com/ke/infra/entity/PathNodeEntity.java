package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * path_node 表映射（V1__core_schema.sql）：路径节点（FR-E05）。
 * parent_node_id 只增不改（分支=对历史节点再挂子，A2 数据面），无 UPDATE 路径故无环。
 * is_new_knowledge=MVP 规则：同会话内该 card_version_id 首次出现=true（01 A1 语义）。
 * visited_at 有 DB 默认值 now()，插入时由应用层显式赋值（与 session.updated_at 同钟）。
 */
@TableName("path_node")
public class PathNodeEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long parentNodeId;
    private Long cardVersionId;
    private Long entryId;
    private String questionText;
    private Boolean isNewKnowledge;
    private OffsetDateTime visitedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getParentNodeId() { return parentNodeId; }
    public void setParentNodeId(Long parentNodeId) { this.parentNodeId = parentNodeId; }
    public Long getCardVersionId() { return cardVersionId; }
    public void setCardVersionId(Long cardVersionId) { this.cardVersionId = cardVersionId; }
    public Long getEntryId() { return entryId; }
    public void setEntryId(Long entryId) { this.entryId = entryId; }
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public Boolean getIsNewKnowledge() { return isNewKnowledge; }
    public void setIsNewKnowledge(Boolean isNewKnowledge) { this.isNewKnowledge = isNewKnowledge; }
    public OffsetDateTime getVisitedAt() { return visitedAt; }
    public void setVisitedAt(OffsetDateTime visitedAt) { this.visitedAt = visitedAt; }
}
