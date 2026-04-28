CREATE TABLE IF NOT EXISTS sf_user_memory (
    id                  UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id           UUID          NOT NULL,
    user_id             UUID          NOT NULL,
    agent_id            UUID,
    project_id          UUID,
    workspace_id        UUID,
    conversation_id     VARCHAR(128),
    execution_id        UUID,
    source_message_id   UUID,
    memory_scope        VARCHAR(30)   NOT NULL DEFAULT 'USER_GLOBAL',
    memory_kind         VARCHAR(30)   NOT NULL,
    source_type         VARCHAR(30)   NOT NULL DEFAULT 'IMPLICIT',
    status              VARCHAR(30)   NOT NULL DEFAULT 'CANDIDATE',
    content             TEXT          NOT NULL,
    structured_value    JSONB,
    content_hash        VARCHAR(64),
    confidence_score    NUMERIC       NOT NULL DEFAULT 0.70,
    importance_score    NUMERIC       NOT NULL DEFAULT 0.50,
    relevance_score     NUMERIC       NOT NULL DEFAULT 1.00,
    sensitivity_level   VARCHAR(20)   NOT NULL DEFAULT 'NORMAL',
    pinned              BOOLEAN       NOT NULL DEFAULT FALSE,
    use_count           INTEGER       NOT NULL DEFAULT 0,
    last_used_at        TIMESTAMP,
    expires_at          TIMESTAMP,
    superseded_by       UUID,
    created_by          UUID,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          UUID,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT sf_user_memory_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS sf_user_memory_profile (
    id                UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL,
    user_id           UUID          NOT NULL,
    agent_id          UUID,
    project_id        UUID,
    profile_scope     VARCHAR(30)   NOT NULL DEFAULT 'USER_GLOBAL',
    profile_markdown  TEXT          NOT NULL,
    profile_json      JSONB,
    version           INTEGER       NOT NULL DEFAULT 1,
    source_memory_ids JSONB,
    generated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        UUID,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        UUID,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT sf_user_memory_profile_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS sf_user_memory_setting (
    id                       UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id                UUID          NOT NULL,
    user_id                  UUID          NOT NULL,
    memory_enabled           BOOLEAN       NOT NULL DEFAULT TRUE,
    reference_saved_memory   BOOLEAN       NOT NULL DEFAULT TRUE,
    reference_chat_history   BOOLEAN       NOT NULL DEFAULT FALSE,
    auto_extract_enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    sensitive_memory_policy  VARCHAR(30)   NOT NULL DEFAULT 'EXPLICIT_ONLY',
    retention_days           INTEGER,
    created_by               UUID,
    created_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by               UUID,
    updated_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                  INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT sf_user_memory_setting_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE sf_user_memory IS '用户长期记忆与偏好表';
COMMENT ON TABLE sf_user_memory_profile IS '用户记忆画像快照表';
COMMENT ON TABLE sf_user_memory_setting IS '用户记忆设置表';

CREATE INDEX IF NOT EXISTS idx_user_memory_user_scope ON sf_user_memory USING btree (tenant_id, user_id, memory_scope, status, deleted);
CREATE INDEX IF NOT EXISTS idx_user_memory_agent ON sf_user_memory USING btree (tenant_id, user_id, agent_id, status, deleted);
CREATE INDEX IF NOT EXISTS idx_user_memory_kind ON sf_user_memory USING btree (tenant_id, user_id, memory_kind, status, deleted);
CREATE INDEX IF NOT EXISTS idx_user_memory_hash ON sf_user_memory USING btree (tenant_id, user_id, content_hash) WHERE deleted = 0 AND content_hash IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_memory_setting_user ON sf_user_memory_setting USING btree (tenant_id, user_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_user_memory_profile_scope ON sf_user_memory_profile USING btree (tenant_id, user_id, profile_scope, agent_id, project_id, deleted);
