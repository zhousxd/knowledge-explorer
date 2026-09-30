-- V2: 卡片摘要检索列 + card_version 不可变（FR-C02/C07/C08）

-- 发布时回填的搜索摘要列
ALTER TABLE card ADD COLUMN summary_text VARCHAR(200);

-- card_version 不可变（触发器兜底，代码层本就只 INSERT）
CREATE OR REPLACE FUNCTION forbid_card_version_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'card_version is immutable (id=%)', OLD.id;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_card_version_immutable
  BEFORE UPDATE OR DELETE ON card_version
  FOR EACH ROW EXECUTE FUNCTION forbid_card_version_mutation();

-- 中文检索用 pg_trgm（01 FR-C02：标题+摘要；zhparser 二期再评估）
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_card_title_trgm ON card USING gin (title gin_trgm_ops);
CREATE INDEX idx_card_summary_trgm ON card USING gin (summary_text gin_trgm_ops);

-- 枚举取值约定注释（P0-3 起 code 统一大写枚举名，写库即枚举 name()）
COMMENT ON COLUMN entry.type IS '枚举 EntryType.name(): LINK_CARD/AGENT_SERVICE/COMPARE';
COMMENT ON COLUMN entry.scope IS 'PRIVATE/PUBLIC';
COMMENT ON COLUMN ke_user.role IS 'UserRole.name(): EXPLORER/CREATOR/EDITOR/OPERATOR';
COMMENT ON COLUMN card.status IS 'CardStatus.name(): DRAFT/PENDING/PUBLISHED/DISABLED';
COMMENT ON COLUMN agent_run.status IS 'AgentRunStatus.name(): QUEUED/RUNNING/DONE/FAILED/TIMEOUT';
