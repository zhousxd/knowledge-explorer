-- 收藏（FR-C10）：用户 ↔ 卡片多对多；UNIQUE(user_id, card_id) 保证幂等收藏。
CREATE TABLE favorite (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES ke_user (id),
    card_id    BIGINT NOT NULL REFERENCES card (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, card_id)
);
CREATE INDEX idx_favorite_user ON favorite (user_id, created_at DESC);
