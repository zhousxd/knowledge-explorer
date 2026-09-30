-- V3: 审计留痕（02 §9 / FR-O03）：@Audited 切面在审核/状态流转/权限变更成功后自动写入。
-- actor_id 可空（无认证上下文时留空，审计不阻断业务）；detail_json 记录方法参数快照。
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    BIGINT,
    action      VARCHAR(50) NOT NULL,
    object_type VARCHAR(20) NOT NULL,
    object_id   BIGINT NOT NULL,
    detail_json JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_object ON audit_log (object_type, object_id, created_at);
