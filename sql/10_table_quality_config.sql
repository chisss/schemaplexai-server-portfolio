-- 质量保障配置表 DDL
-- 创建时间: 2026-03-22

-- 质量评估维度表
CREATE TABLE IF NOT EXISTS sf_quality_dimension (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    issue_type VARCHAR(30) NOT NULL,
    description TEXT,
    sort_order INTEGER DEFAULT 0,
    status VARCHAR(20) DEFAULT 'active',
    is_builtin BOOLEAN DEFAULT FALSE,
    ext_config JSONB,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_quality_dim_tenant_code ON sf_quality_dimension(tenant_id, code) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_quality_dim_issue_type ON sf_quality_dimension(issue_type);
CREATE INDEX IF NOT EXISTS idx_quality_dim_status ON sf_quality_dimension(status);

COMMENT ON TABLE sf_quality_dimension IS '质量评估维度表';
COMMENT ON COLUMN sf_quality_dimension.code IS '维度编码（租户内唯一）';
COMMENT ON COLUMN sf_quality_dimension.issue_type IS '问题类型: deviation/intent_defect/both';
COMMENT ON COLUMN sf_quality_dimension.status IS '状态: active/inactive';

-- 质量评估规则表
CREATE TABLE IF NOT EXISTS sf_quality_rule (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    dimension_id UUID,
    dimension_code VARCHAR(100) NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    rule_type VARCHAR(50) NOT NULL,
    trigger_mode VARCHAR(30),
    severity VARCHAR(20) DEFAULT 'warning',
    weight INTEGER DEFAULT 100,
    condition_expr TEXT,
    rule_config JSONB,
    status VARCHAR(20) DEFAULT 'active',
    is_builtin BOOLEAN DEFAULT FALSE,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_quality_rule_tenant_code ON sf_quality_rule(tenant_id, code) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_quality_rule_dimension_code ON sf_quality_rule(dimension_code);
CREATE INDEX IF NOT EXISTS idx_quality_rule_type ON sf_quality_rule(rule_type);
CREATE INDEX IF NOT EXISTS idx_quality_rule_trigger_mode ON sf_quality_rule(trigger_mode);

COMMENT ON TABLE sf_quality_rule IS '质量评估规则表';
COMMENT ON COLUMN sf_quality_rule.rule_type IS '规则类型: structural/semantic/performance/security/ambiguity/contradiction/omission/vagueness/custom';
COMMENT ON COLUMN sf_quality_rule.trigger_mode IS '触发模式: manual/agent_execution/workflow_node/scheduled/event';
COMMENT ON COLUMN sf_quality_rule.severity IS '默认严重级别: critical/warning/info';

-- 质量评估档案表
CREATE TABLE IF NOT EXISTS sf_quality_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    issue_type VARCHAR(30) DEFAULT 'both',
    description TEXT,
    trigger_modes JSONB,
    enabled_dimension_codes JSONB,
    enabled_rule_codes JSONB,
    threshold_config JSONB,
    status VARCHAR(20) DEFAULT 'active',
    is_default BOOLEAN DEFAULT FALSE,
    is_builtin BOOLEAN DEFAULT FALSE,
    version INTEGER DEFAULT 1,
    published_at TIMESTAMP,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_quality_profile_tenant_code ON sf_quality_profile(tenant_id, code) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_quality_profile_issue_type ON sf_quality_profile(issue_type);
CREATE INDEX IF NOT EXISTS idx_quality_profile_status ON sf_quality_profile(status);

COMMENT ON TABLE sf_quality_profile IS '质量评估档案表';
COMMENT ON COLUMN sf_quality_profile.issue_type IS '问题类型: deviation/intent_defect/both';
COMMENT ON COLUMN sf_quality_profile.trigger_modes IS '触发模式集合';
COMMENT ON COLUMN sf_quality_profile.enabled_dimension_codes IS '启用的维度编码集合';
COMMENT ON COLUMN sf_quality_profile.enabled_rule_codes IS '启用的规则编码集合';

-- 质量任务表
CREATE TABLE IF NOT EXISTS sf_quality_task (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    task_no VARCHAR(50) NOT NULL,
    issue_type VARCHAR(30) NOT NULL,
    trigger_mode VARCHAR(30),
    source_type VARCHAR(30),
    source_agent_id UUID,
    spec_id UUID,
    task_id UUID,
    agent_execution_id UUID,
    profile_id UUID,
    profile_code VARCHAR(100),
    status VARCHAR(20) DEFAULT 'pending',
    progress INTEGER DEFAULT 0,
    total_items INTEGER DEFAULT 0,
    success_items INTEGER DEFAULT 0,
    failed_items INTEGER DEFAULT 0,
    skipped_items INTEGER DEFAULT 0,
    request_payload JSONB,
    result_summary JSONB,
    error_message TEXT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_quality_task_no ON sf_quality_task(task_no) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_quality_task_spec_id ON sf_quality_task(spec_id);
CREATE INDEX IF NOT EXISTS idx_quality_task_status ON sf_quality_task(status);
CREATE INDEX IF NOT EXISTS idx_quality_task_trigger_mode ON sf_quality_task(trigger_mode);
CREATE INDEX IF NOT EXISTS idx_quality_task_source_type ON sf_quality_task(source_type);

COMMENT ON TABLE sf_quality_task IS '质量任务表';
COMMENT ON COLUMN sf_quality_task.issue_type IS '问题类型: deviation/intent_defect/both';
COMMENT ON COLUMN sf_quality_task.trigger_mode IS '触发模式: manual/agent_execution/workflow_node/scheduled/event';
COMMENT ON COLUMN sf_quality_task.source_type IS '来源类型: manual/agent/workflow/system';
COMMENT ON COLUMN sf_quality_task.status IS '任务状态: pending/running/succeeded/failed/cancelled';

-- 扩展现有偏离表
ALTER TABLE sf_quality_deviation
    ADD COLUMN IF NOT EXISTS dimension_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS rule_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS source_agent_id UUID;

CREATE INDEX IF NOT EXISTS idx_quality_dev_dimension_code ON sf_quality_deviation(dimension_code);
CREATE INDEX IF NOT EXISTS idx_quality_dev_rule_code ON sf_quality_deviation(rule_code);
CREATE INDEX IF NOT EXISTS idx_quality_dev_source_type ON sf_quality_deviation(source_type);

-- 扩展现有意图缺陷表
ALTER TABLE sf_intent_defect
    ADD COLUMN IF NOT EXISTS dimension_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS rule_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS source_agent_id UUID;

CREATE INDEX IF NOT EXISTS idx_intent_defect_dimension_code ON sf_intent_defect(dimension_code);
CREATE INDEX IF NOT EXISTS idx_intent_defect_rule_code ON sf_intent_defect(rule_code);
CREATE INDEX IF NOT EXISTS idx_intent_defect_source_type ON sf_intent_defect(source_type);
