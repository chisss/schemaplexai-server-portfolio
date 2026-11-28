package com.schemaplexai.service.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 租户内 Agent 的结构化事实，在跨会话提示中按需读取。 */
@Service
@RequiredArgsConstructor
public class AgentFactMemoryService {
    private final JdbcTemplate jdbc;

    public List<Fact> list(String tenantId, String agentId) {
        if (tenantId == null || agentId == null) return List.of();
        return jdbc.query("""
                SELECT fact_key, fact_value::text
                  FROM sf_agent_fact_memory
                 WHERE tenant_id = ? AND agent_id = ?
                 ORDER BY updated_at DESC, fact_key
                 LIMIT 20
                """, (row, index) -> new Fact(row.getString(1), row.getString(2)), parseId(tenantId), parseId(agentId));
    }

    public void save(String tenantId, String agentId, String key, JsonNode value) {
        if (key == null || !key.matches("[a-zA-Z0-9._-]{1,80}") || value == null || value.isNull()
                || value.toString().length() > 4_096) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "事实名称或 JSON 内容超出范围");
        }
        int updated = jdbc.update("""
                INSERT INTO sf_agent_fact_memory (tenant_id, agent_id, fact_key, fact_value)
                SELECT tenant_id, id, ?, ?::jsonb FROM sf_agent
                 WHERE tenant_id = ? AND id = ? AND deleted = 0
                ON CONFLICT (tenant_id, agent_id, fact_key)
                DO UPDATE SET fact_value = EXCLUDED.fact_value, updated_at = clock_timestamp()
                """, key, value.toString(), parseId(tenantId), parseId(agentId));
        if (updated == 0) throw new BusinessException(ResultCode.AGENT_NOT_FOUND);
    }

    public void remove(String tenantId, String agentId, String key) {
        jdbc.update("""
                DELETE FROM sf_agent_fact_memory
                 WHERE tenant_id = ? AND agent_id = ? AND fact_key = ?
                """, parseId(tenantId), parseId(agentId), key);
    }

    private UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Agent 或租户标识无效");
        }
    }

    public record Fact(String key, String jsonValue) { }
}
