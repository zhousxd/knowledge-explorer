-- 用户与角色（FR-U01/U03：EXPLORER/CREATOR/EDITOR/OPERATOR）
CREATE TABLE ke_user (
    id            BIGSERIAL PRIMARY KEY,
    phone         VARCHAR(20) UNIQUE,
    password_hash VARCHAR(100),
    nickname      VARCHAR(50)  NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'EXPLORER',
    status        VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 卡片与版本（FR-C07/C08：版本不可变，只允许 INSERT）
CREATE TABLE card (
    id                 BIGSERIAL PRIMARY KEY,
    theme              VARCHAR(50)  NOT NULL,
    template_type      VARCHAR(20)  NOT NULL,
    title              VARCHAR(120) NOT NULL,
    status             VARCHAR(12)  NOT NULL DEFAULT 'DRAFT',
    current_version_id BIGINT,
    maintainer_id      BIGINT REFERENCES ke_user (id),
    sort               INT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_card_theme_status ON card (theme, status);

CREATE TABLE card_version (
    id           BIGSERIAL PRIMARY KEY,
    card_id      BIGINT NOT NULL REFERENCES card (id),
    version_no   INT NOT NULL,
    content_json JSONB NOT NULL,
    sources      JSONB,
    created_by   BIGINT REFERENCES ke_user (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (card_id, version_no)
);

-- 入口（FR-N07 白名单类型：link_card / agent_service / compare）
CREATE TABLE entry (
    id             BIGSERIAL PRIMARY KEY,
    card_id        BIGINT REFERENCES card (id),
    name           VARCHAR(60) NOT NULL,
    type           VARCHAR(20) NOT NULL,
    relation_label VARCHAR(20),
    target_card_id BIGINT REFERENCES card (id),
    service_type   VARCHAR(30),
    config_json    JSONB,
    scope          VARCHAR(10) NOT NULL DEFAULT 'PRIVATE',
    status         VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    version        INT NOT NULL DEFAULT 1,
    author_id      BIGINT REFERENCES ke_user (id),
    sort           INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entry_card ON entry (card_id, status);

-- 知识单元与引用（FR-O01/O02：locator 保留卷/章/页/时间戳）
CREATE TABLE knowledge_asset (
    id             BIGSERIAL PRIMARY KEY,
    kind           VARCHAR(12)  NOT NULL,
    title          VARCHAR(200) NOT NULL,
    source_meta    JSONB,
    locator        JSONB,
    license        VARCHAR(50),
    license_expire DATE,
    content_extract TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE citation (
    id          BIGSERIAL PRIMARY KEY,
    asset_id    BIGINT NOT NULL REFERENCES knowledge_asset (id),
    locator     JSONB,
    quote       TEXT,
    object_type VARCHAR(20) NOT NULL,
    object_id   BIGINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_citation_object ON citation (object_type, object_id);

-- 会话与路径树（FR-E03/E05：分支不建表，parent_node_id 自然成树）
CREATE TABLE exploration_session (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES ke_user (id),
    theme           VARCHAR(50),
    goal            TEXT,
    explain_level   VARCHAR(10) NOT NULL DEFAULT 'SIMPLE',
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    origin_share_id BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_session_user ON exploration_session (user_id, status);

CREATE TABLE path_node (
    id               BIGSERIAL PRIMARY KEY,
    session_id       BIGINT NOT NULL REFERENCES exploration_session (id),
    parent_node_id   BIGINT REFERENCES path_node (id),
    card_version_id  BIGINT REFERENCES card_version (id),
    entry_id         BIGINT REFERENCES entry (id),
    question_text    TEXT,
    is_new_knowledge BOOLEAN,
    visited_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_node_session ON path_node (session_id);
CREATE INDEX idx_node_parent ON path_node (parent_node_id);

-- 智能体任务（FR-S04：既是队列又是执行记录）
CREATE TABLE agent_run (
    id           BIGSERIAL PRIMARY KEY,
    session_id   BIGINT REFERENCES exploration_session (id),
    node_id      BIGINT REFERENCES path_node (id),
    service_type VARCHAR(30) NOT NULL,
    input_json   JSONB,
    status       VARCHAR(10) NOT NULL DEFAULT 'QUEUED',
    artifact_ids JSONB,
    model        VARCHAR(60),
    tokens_in    INT,
    tokens_out   INT,
    cost         NUMERIC(10, 4),
    latency_ms   INT,
    error        TEXT,
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_run_status ON agent_run (status);
CREATE INDEX idx_run_session ON agent_run (session_id);

-- 成果与分享（FR-S03 / FR-H03：快照不可变 JSON）
CREATE TABLE artifact (
    id            BIGSERIAL PRIMARY KEY,
    session_id    BIGINT NOT NULL REFERENCES exploration_session (id),
    type          VARCHAR(20) NOT NULL,
    content_json  JSONB NOT NULL,
    cited_run_ids JSONB,
    status        VARCHAR(12) NOT NULL DEFAULT 'DRAFT',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE share (
    id          BIGSERIAL PRIMARY KEY,
    token       VARCHAR(21) NOT NULL UNIQUE,
    object_type VARCHAR(20) NOT NULL,
    object_id   BIGINT NOT NULL,
    user_id     BIGINT NOT NULL REFERENCES ke_user (id),
    visibility  VARCHAR(10) NOT NULL DEFAULT 'PUBLIC',
    revoked     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE share_snapshot (
    id            BIGSERIAL PRIMARY KEY,
    share_id      BIGINT NOT NULL REFERENCES share (id),
    snapshot_json JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 审核队列（FR-O03）
CREATE TABLE review_task (
    id          BIGSERIAL PRIMARY KEY,
    object_type VARCHAR(12) NOT NULL,
    object_id   BIGINT NOT NULL,
    action      VARCHAR(20),
    status      VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    reviewer_id BIGINT REFERENCES ke_user (id),
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_review_status ON review_task (status);
