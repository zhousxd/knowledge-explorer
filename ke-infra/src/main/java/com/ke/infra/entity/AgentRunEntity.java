package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * agent_run 表映射（V1__core_schema.sql）：既是队列又是执行记录（FR-S04）。
 * input_json / artifact_ids 为 JSONB 列，此处以 JSON 字符串读写；
 * 时间列 TIMESTAMPTZ 均有 DB 默认值 now()，插入时留 null 即可。
 */
@TableName("agent_run")
public class AgentRunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long nodeId;
    private String serviceType;
    private String inputJson;
    private String status;      // AgentRunStatus.name()
    private String artifactIds;
    private String model;
    private Integer tokensIn;
    private Integer tokensOut;
    private BigDecimal cost;
    private Integer latencyMs;
    private String error;
    private OffsetDateTime heartbeatAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getServiceType() { return serviceType; }
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getArtifactIds() { return artifactIds; }
    public void setArtifactIds(String artifactIds) { this.artifactIds = artifactIds; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getTokensIn() { return tokensIn; }
    public void setTokensIn(Integer tokensIn) { this.tokensIn = tokensIn; }
    public Integer getTokensOut() { return tokensOut; }
    public void setTokensOut(Integer tokensOut) { this.tokensOut = tokensOut; }
    public BigDecimal getCost() { return cost; }
    public void setCost(BigDecimal cost) { this.cost = cost; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public OffsetDateTime getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(OffsetDateTime heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
