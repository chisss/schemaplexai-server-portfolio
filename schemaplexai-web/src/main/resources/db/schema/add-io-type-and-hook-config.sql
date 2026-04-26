-- ============================================================
-- 迁移: 新增 sf_builtin_tool.io_type 列 + sf_agent_hook_config 表
-- ============================================================

-- 1. sf_builtin_tool 新增 io_type 列
ALTER TABLE sf_builtin_tool ADD COLUMN IF NOT EXISTS io_type VARCHAR(16) NOT NULL DEFAULT 'read_write';
COMMENT ON COLUMN sf_builtin_tool.io_type IS '工具IO类型: read=只读, write=只写, read_write=读写';

-- 2. 初始化各内置工具的 io_type
UPDATE sf_builtin_tool SET io_type = 'read'  WHERE code IN ('sys.read', 'sys.glob', 'sys.grep', 'sys.ls', 'sys.stat');
UPDATE sf_builtin_tool SET io_type = 'write' WHERE code IN ('sys.write', 'sys.edit', 'sys.mkdir', 'sys.rm', 'sys.cp', 'sys.mv');
UPDATE sf_builtin_tool SET io_type = 'read_write' WHERE code IN ('sys.bash', 'web.fetch', 'code.exec');

-- 3. sf_agent_hook_config 表
CREATE TABLE IF NOT EXISTS sf_agent_hook_config (
    id          VARCHAR(32) PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    agent_id    VARCHAR(64)  NOT NULL,
    hook_code   VARCHAR(100) NOT NULL,
    hook_type   VARCHAR(50)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    priority    INTEGER      NOT NULL DEFAULT 0,
    config_json TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE  sf_agent_hook_config IS 'Agent Hook 配置表，按 Agent 维度控制 Hook 启停与优先级';
COMMENT ON COLUMN sf_agent_hook_config.id          IS '主键ID';
COMMENT ON COLUMN sf_agent_hook_config.tenant_id   IS '租户ID';
COMMENT ON COLUMN sf_agent_hook_config.agent_id    IS 'Agent ID';
COMMENT ON COLUMN sf_agent_hook_config.hook_code   IS 'Hook 代码，对应 AgentHook.name()';
COMMENT ON COLUMN sf_agent_hook_config.hook_type   IS 'Hook 类型: BEFORE_MODEL_CALL / AFTER_MODEL_CALL / BEFORE_TOOL_EXECUTE / AFTER_TOOL_EXECUTE / ON_LOOP_COMPLETE';
COMMENT ON COLUMN sf_agent_hook_config.enabled     IS '是否启用';
COMMENT ON COLUMN sf_agent_hook_config.priority    IS '执行优先级，越小越先执行';
COMMENT ON COLUMN sf_agent_hook_config.config_json IS 'Hook 自定义参数 JSON';
COMMENT ON COLUMN sf_agent_hook_config.created_at  IS '创建时间';
COMMENT ON COLUMN sf_agent_hook_config.updated_at  IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_hook_config_agent ON sf_agent_hook_config(tenant_id, agent_id);
