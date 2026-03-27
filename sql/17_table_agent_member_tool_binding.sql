-- =====================================================
-- 团队成员工具绑定表
-- 日期: 2026-03-25
-- 说明: 为 Team Agent 的每个团队成员单独配置工具绑定
-- =====================================================

CREATE TABLE IF NOT EXISTS sf_agent_team_member_tool_binding (
    id              VARCHAR(64)    PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    tenant_id       VARCHAR(64),
    member_id       VARCHAR(64)   NOT NULL,
    tool_code       VARCHAR(128)  NOT NULL,
    source_type     VARCHAR(32)   NOT NULL,
    source_ref_id   VARCHAR(64),
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    priority        INTEGER       NOT NULL DEFAULT 100,
    config_override JSONB,
    created_by      VARCHAR(64),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64),
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INTEGER       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_member_tool_member ON sf_agent_team_member_tool_binding(member_id);
CREATE INDEX IF NOT EXISTS idx_member_tool_agent_member ON sf_agent_team_member_tool_binding(member_id, deleted);
CREATE INDEX IF NOT EXISTS idx_member_tool_tenant ON sf_agent_team_member_tool_binding(tenant_id);

-- 注释
COMMENT ON TABLE sf_agent_team_member_tool_binding IS 'Agent团队成员工具绑定表';
COMMENT ON COLUMN sf_agent_team_member_tool_binding.tenant_id IS '租户ID';
COMMENT ON COLUMN sf_agent_team_member_tool_binding.member_id IS '关联团队成员ID';
COMMENT ON COLUMN sf_agent_team_member_tool_binding.tool_code IS '工具代码';
COMMENT ON COLUMN sf_agent_team_member_tool_binding.source_type IS '来源类型: builtin/skill/mcp';
COMMENT ON COLUMN sf_agent_team_member_tool_binding.source_ref_id IS '来源ID: MCP Server ID / Skill ID（builtin时为空）';
