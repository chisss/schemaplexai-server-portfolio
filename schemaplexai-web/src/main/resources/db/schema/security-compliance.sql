CREATE TABLE IF NOT EXISTS sf_security_policy (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    policy_code VARCHAR(100) NOT NULL,
    policy_name VARCHAR(200) NOT NULL,
    domain_code VARCHAR(50) NOT NULL,
    policy_type VARCHAR(100) NOT NULL,
    policy_scope VARCHAR(50) NOT NULL DEFAULT 'tenant',
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    enforcement_mode VARCHAR(20) NOT NULL DEFAULT 'audit',
    risk_level VARCHAR(20) NOT NULL DEFAULT 'medium',
    version INTEGER NOT NULL DEFAULT 0,
    is_builtin BOOLEAN NOT NULL DEFAULT FALSE,
    control_points JSONB,
    tags JSONB,
    target_selector JSONB,
    policy_config JSONB,
    description TEXT,
    last_published_at TIMESTAMP,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);
COMMENT ON TABLE sf_security_policy IS '安全策略表';
COMMENT ON COLUMN sf_security_policy.tenant_id IS '租户ID';
COMMENT ON COLUMN sf_security_policy.policy_code IS '策略编码，租户内唯一';
COMMENT ON COLUMN sf_security_policy.policy_name IS '策略名称';
COMMENT ON COLUMN sf_security_policy.domain_code IS '安全域: runtime/data/risk/industry';
COMMENT ON COLUMN sf_security_policy.policy_type IS '策略类型';
COMMENT ON COLUMN sf_security_policy.policy_scope IS '生效范围: tenant/agent/workflow/project';
COMMENT ON COLUMN sf_security_policy.status IS '状态: draft/active/inactive/archived';
COMMENT ON COLUMN sf_security_policy.enforcement_mode IS '执行模式: audit/warn/block';
COMMENT ON COLUMN sf_security_policy.risk_level IS '风险等级: low/medium/high/critical';
COMMENT ON COLUMN sf_security_policy.version IS '已发布版本号';
COMMENT ON COLUMN sf_security_policy.is_builtin IS '是否内置策略';
COMMENT ON COLUMN sf_security_policy.control_points IS '控制点列表';
COMMENT ON COLUMN sf_security_policy.tags IS '标签列表';
COMMENT ON COLUMN sf_security_policy.target_selector IS '目标选择器';
COMMENT ON COLUMN sf_security_policy.policy_config IS '策略配置';
COMMENT ON COLUMN sf_security_policy.description IS '策略说明';
COMMENT ON COLUMN sf_security_policy.last_published_at IS '最近发布时间';
COMMENT ON COLUMN sf_security_policy.created_by IS '创建人';
COMMENT ON COLUMN sf_security_policy.updated_by IS '更新人';

CREATE UNIQUE INDEX IF NOT EXISTS uk_security_policy_tenant_code
    ON sf_security_policy(tenant_id, policy_code)
    WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_security_policy_domain_status
    ON sf_security_policy(tenant_id, domain_code, status);
CREATE INDEX IF NOT EXISTS idx_security_policy_risk_level
    ON sf_security_policy(tenant_id, risk_level);
CREATE INDEX IF NOT EXISTS idx_security_policy_updated_at
    ON sf_security_policy(tenant_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS sf_security_audit_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_source VARCHAR(50) NOT NULL,
    event_status VARCHAR(20) NOT NULL,
    risk_level VARCHAR(20),
    domain_code VARCHAR(50),
    policy_id UUID,
    policy_code VARCHAR(100),
    resource_type VARCHAR(50),
    resource_id VARCHAR(128),
    event_title VARCHAR(200) NOT NULL,
    event_detail TEXT,
    actor_user_id UUID,
    actor_username VARCHAR(100),
    client_ip VARCHAR(64),
    user_agent TEXT,
    metadata JSONB,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE sf_security_audit_event IS '安全审计事件表';
COMMENT ON COLUMN sf_security_audit_event.tenant_id IS '租户ID';
COMMENT ON COLUMN sf_security_audit_event.trace_id IS '链路追踪ID';
COMMENT ON COLUMN sf_security_audit_event.event_type IS '事件类型';
COMMENT ON COLUMN sf_security_audit_event.event_source IS '事件来源';
COMMENT ON COLUMN sf_security_audit_event.event_status IS '事件状态: success/warning/blocked/failed';
COMMENT ON COLUMN sf_security_audit_event.risk_level IS '风险等级';
COMMENT ON COLUMN sf_security_audit_event.domain_code IS '安全域';
COMMENT ON COLUMN sf_security_audit_event.policy_id IS '关联策略ID';
COMMENT ON COLUMN sf_security_audit_event.policy_code IS '关联策略编码';
COMMENT ON COLUMN sf_security_audit_event.resource_type IS '资源类型';
COMMENT ON COLUMN sf_security_audit_event.resource_id IS '资源ID';
COMMENT ON COLUMN sf_security_audit_event.event_title IS '事件标题';
COMMENT ON COLUMN sf_security_audit_event.event_detail IS '事件明细';
COMMENT ON COLUMN sf_security_audit_event.actor_user_id IS '操作人ID';
COMMENT ON COLUMN sf_security_audit_event.actor_username IS '操作人用户名';
COMMENT ON COLUMN sf_security_audit_event.client_ip IS '客户端IP';
COMMENT ON COLUMN sf_security_audit_event.user_agent IS '客户端UA';
COMMENT ON COLUMN sf_security_audit_event.metadata IS '扩展元数据';
COMMENT ON COLUMN sf_security_audit_event.occurred_at IS '发生时间';

CREATE INDEX IF NOT EXISTS idx_security_audit_event_tenant_time
    ON sf_security_audit_event(tenant_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_security_audit_event_trace
    ON sf_security_audit_event(trace_id);
CREATE INDEX IF NOT EXISTS idx_security_audit_event_type_status
    ON sf_security_audit_event(tenant_id, event_type, event_status);
CREATE INDEX IF NOT EXISTS idx_security_audit_event_domain
    ON sf_security_audit_event(tenant_id, domain_code);
ALTER TABLE sf_security_audit_event
    ALTER COLUMN resource_id TYPE VARCHAR(128)
    USING resource_id::text;

CREATE TABLE IF NOT EXISTS sf_security_policy_version (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    snapshot_config JSONB,
    snapshot_targets JSONB,
    change_summary VARCHAR(500),
    published_by UUID,
    published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);
COMMENT ON TABLE sf_security_policy_version IS '安全策略版本快照表';
COMMENT ON COLUMN sf_security_policy_version.policy_id IS '策略ID';
COMMENT ON COLUMN sf_security_policy_version.version_no IS '版本号';
COMMENT ON COLUMN sf_security_policy_version.snapshot_config IS '策略配置快照';
COMMENT ON COLUMN sf_security_policy_version.snapshot_targets IS '目标快照';
COMMENT ON COLUMN sf_security_policy_version.change_summary IS '变更摘要';
COMMENT ON COLUMN sf_security_policy_version.published_by IS '发布人';
COMMENT ON COLUMN sf_security_policy_version.published_at IS '发布时间';
CREATE UNIQUE INDEX IF NOT EXISTS uk_security_policy_version_no
    ON sf_security_policy_version(policy_id, version_no)
    WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_security_policy_version_published_at
    ON sf_security_policy_version(tenant_id, published_at DESC);

CREATE TABLE IF NOT EXISTS sf_security_binding (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_id UUID NOT NULL,
    source_code VARCHAR(100),
    source_name VARCHAR(200),
    binding_type VARCHAR(50) NOT NULL,
    target_id UUID,
    target_name VARCHAR(200),
    domain_code VARCHAR(50),
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    priority INTEGER NOT NULL DEFAULT 100,
    effective_from TIMESTAMP,
    effective_to TIMESTAMP,
    binding_config JSONB,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);
COMMENT ON TABLE sf_security_binding IS '安全策略与规则包绑定关系表';
COMMENT ON COLUMN sf_security_binding.source_type IS '来源类型: policy/rule_pack';
COMMENT ON COLUMN sf_security_binding.source_id IS '来源ID';
COMMENT ON COLUMN sf_security_binding.binding_type IS '绑定类型: tenant/agent/workflow/project';
COMMENT ON COLUMN sf_security_binding.target_id IS '目标ID';
COMMENT ON COLUMN sf_security_binding.target_name IS '目标名称';
COMMENT ON COLUMN sf_security_binding.priority IS '优先级，越小越高';
COMMENT ON COLUMN sf_security_binding.binding_config IS '绑定扩展配置';
CREATE INDEX IF NOT EXISTS idx_security_binding_source
    ON sf_security_binding(tenant_id, source_type, source_id, status);
CREATE INDEX IF NOT EXISTS idx_security_binding_target
    ON sf_security_binding(tenant_id, binding_type, target_id, status);

CREATE TABLE IF NOT EXISTS sf_security_incident (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    incident_no VARCHAR(64) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    domain_code VARCHAR(50) NOT NULL,
    risk_level VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'new',
    source_type VARCHAR(50) NOT NULL,
    source_id VARCHAR(128),
    source_name VARCHAR(200),
    policy_id UUID,
    policy_code VARCHAR(100),
    decision VARCHAR(20) NOT NULL,
    event_title VARCHAR(200) NOT NULL,
    event_detail TEXT,
    assigned_to UUID,
    assigned_name VARCHAR(100),
    resolved_at TIMESTAMP,
    resolution_summary VARCHAR(500),
    payload JSONB,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);
COMMENT ON TABLE sf_security_incident IS '安全风控事件主表';
COMMENT ON COLUMN sf_security_incident.incident_no IS '事件编号';
COMMENT ON COLUMN sf_security_incident.trace_id IS '关联链路追踪ID';
COMMENT ON COLUMN sf_security_incident.status IS '事件状态: new/assigned/investigating/resolved/ignored/escalated';
COMMENT ON COLUMN sf_security_incident.source_type IS '来源资源类型';
COMMENT ON COLUMN sf_security_incident.source_id IS '来源资源ID';
COMMENT ON COLUMN sf_security_incident.decision IS '安全决策: warn/block/pause';
COMMENT ON COLUMN sf_security_incident.payload IS '事件快照';
CREATE UNIQUE INDEX IF NOT EXISTS uk_security_incident_no
    ON sf_security_incident(tenant_id, incident_no)
    WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_security_incident_status
    ON sf_security_incident(tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_security_incident_trace
    ON sf_security_incident(trace_id);
ALTER TABLE sf_security_incident
    ALTER COLUMN source_id TYPE VARCHAR(128)
    USING source_id::text;

CREATE TABLE IF NOT EXISTS sf_security_incident_action (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    incident_id UUID NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    action_result VARCHAR(50),
    comment VARCHAR(1000),
    metadata JSONB,
    operator_id UUID,
    operator_name VARCHAR(100),
    action_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);
COMMENT ON TABLE sf_security_incident_action IS '安全风控事件处置记录表';
COMMENT ON COLUMN sf_security_incident_action.incident_id IS '事件ID';
COMMENT ON COLUMN sf_security_incident_action.action_type IS '动作类型: assign/resolve/ignore/escalate/comment/resume_resource';
COMMENT ON COLUMN sf_security_incident_action.action_result IS '动作结果';
COMMENT ON COLUMN sf_security_incident_action.comment IS '处置说明';
COMMENT ON COLUMN sf_security_incident_action.metadata IS '扩展元数据';
CREATE INDEX IF NOT EXISTS idx_security_incident_action_incident
    ON sf_security_incident_action(tenant_id, incident_id, action_at DESC);

CREATE TABLE IF NOT EXISTS sf_security_rule_pack (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    pack_code VARCHAR(100) NOT NULL,
    pack_name VARCHAR(200) NOT NULL,
    industry_code VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    pack_version INTEGER NOT NULL DEFAULT 0,
    default_action VARCHAR(20) NOT NULL DEFAULT 'warn',
    is_builtin BOOLEAN NOT NULL DEFAULT FALSE,
    description TEXT,
    pack_config JSONB,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);
COMMENT ON TABLE sf_security_rule_pack IS '安全行业规则包表';
COMMENT ON COLUMN sf_security_rule_pack.pack_code IS '规则包编码';
COMMENT ON COLUMN sf_security_rule_pack.industry_code IS '行业编码';
COMMENT ON COLUMN sf_security_rule_pack.status IS '状态: draft/active/inactive/archived';
COMMENT ON COLUMN sf_security_rule_pack.default_action IS '默认动作: warn/block/pause';
COMMENT ON COLUMN sf_security_rule_pack.pack_config IS '规则包配置';
CREATE UNIQUE INDEX IF NOT EXISTS uk_security_rule_pack_code
    ON sf_security_rule_pack(tenant_id, pack_code)
    WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_security_rule_pack_status
    ON sf_security_rule_pack(tenant_id, status, industry_code);

CREATE TABLE IF NOT EXISTS sf_security_rule_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    pack_id UUID NOT NULL,
    item_code VARCHAR(100) NOT NULL,
    item_name VARCHAR(200) NOT NULL,
    rule_source VARCHAR(200),
    rule_clause VARCHAR(500),
    risk_level VARCHAR(20) NOT NULL DEFAULT 'medium',
    action VARCHAR(20) NOT NULL DEFAULT 'warn',
    match_type VARCHAR(20) NOT NULL DEFAULT 'keyword',
    match_content TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    item_config JSONB,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);
COMMENT ON TABLE sf_security_rule_item IS '安全行业规则项表';
COMMENT ON COLUMN sf_security_rule_item.pack_id IS '规则包ID';
COMMENT ON COLUMN sf_security_rule_item.item_code IS '规则项编码';
COMMENT ON COLUMN sf_security_rule_item.match_type IS '匹配方式: keyword/regex/exact';
COMMENT ON COLUMN sf_security_rule_item.match_content IS '匹配内容';
COMMENT ON COLUMN sf_security_rule_item.item_config IS '规则项扩展配置';
CREATE UNIQUE INDEX IF NOT EXISTS uk_security_rule_item_code
    ON sf_security_rule_item(pack_id, item_code)
    WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_security_rule_item_pack
    ON sf_security_rule_item(tenant_id, pack_id, enabled, sort_order);
