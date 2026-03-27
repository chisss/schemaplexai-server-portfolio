-- 工具执行审计日志表
CREATE TABLE IF NOT EXISTS sf_tool_execution_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64),
    agent_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(128),
    tool_call_id VARCHAR(128) NOT NULL,
    tool_type VARCHAR(32) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    provider VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    start_at TIMESTAMP NOT NULL,
    end_at TIMESTAMP,
    latency_ms BIGINT,
    request_payload JSONB,
    response_payload JSONB,
    error_code VARCHAR(64),
    error_message TEXT,
    trace_id VARCHAR(128),
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tool_log_tenant_time ON sf_tool_execution_log(tenant_id, created_at);
CREATE INDEX idx_tool_log_agent_time ON sf_tool_execution_log(agent_id, created_at);
CREATE INDEX idx_tool_log_call_id ON sf_tool_execution_log(tool_call_id);
CREATE INDEX idx_tool_log_status_time ON sf_tool_execution_log(status, created_at);
