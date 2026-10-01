package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * share 表映射（V1__core_schema.sql）：分享（FR-H01/H03）。
 * token 为 21 位 Base62（SecureRandom，不可枚举），UNIQUE 兜底由 ShareService 换号重试；
 * object_type MVP 仅 'SESSION'（object_id=exploration_session.id）；visibility 固定 'PUBLIC'
 * （MVP 无私有分享开关）；revoked=true 后匿名浏览与撤销语义见 ShareService（统一 404 不泄露存在性）。
 * 快照正文不在本表（share_snapshot 一行一版，INSERT-only 不可变）。
 */
@TableName("share")
public class ShareEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String token;
    private String objectType;
    private Long objectId;
    private Long userId;
    private String visibility;
    private Boolean revoked;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public Long getObjectId() { return objectId; }
    public void setObjectId(Long objectId) { this.objectId = objectId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public Boolean getRevoked() { return revoked; }
    public void setRevoked(Boolean revoked) { this.revoked = revoked; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
