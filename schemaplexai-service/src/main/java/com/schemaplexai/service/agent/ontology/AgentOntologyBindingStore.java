package com.schemaplexai.service.agent.ontology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.model.dto.agent.AgentOntologyBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** 按租户保管绑定配置及独立的执行快照。 */
@Repository
@RequiredArgsConstructor
class AgentOntologyBindingStore {

    private static final TypeReference<List<AgentOntologyBinding>> BINDINGS = new TypeReference<>() { };
    private static final TypeReference<List<Release>> RELEASES = new TypeReference<>() { };
    private static final Pattern COMPACT_UUID = Pattern.compile("([0-9a-f]{8})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{12})", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    Optional<AgentOntologyBindingService.Bindings> findBindings(String tenantId, String agentId) {
        return jdbc.query("""
                SELECT revision, bindings FROM sf_agent_ontology_binding WHERE tenant_id=? AND agent_id=?
                """, (row, index) -> new AgentOntologyBindingService.Bindings(row.getLong("revision"),
                decode(row.getString("bindings"), BINDINGS)), uuid(tenantId), uuid(agentId))
                .stream().findFirst();
    }

    boolean replaceBindings(String tenantId, String agentId, long expectedRevision, List<AgentOntologyBinding> bindings) {
        UUID tenant = uuid(tenantId);
        UUID agent = uuid(agentId);
        String payload = encode(bindings);
        if (expectedRevision == 0) {
            return jdbc.update("""
                    INSERT INTO sf_agent_ontology_binding (tenant_id, agent_id, revision, bindings)
                    VALUES (?, ?, 1, CAST(? AS jsonb)) ON CONFLICT (tenant_id, agent_id) DO NOTHING
                    """, tenant, agent, payload) == 1;
        }
        return jdbc.update("""
                UPDATE sf_agent_ontology_binding SET revision=revision+1, bindings=CAST(? AS jsonb)
                WHERE tenant_id=? AND agent_id=? AND revision=?
                """, payload, tenant, agent, expectedRevision) == 1;
    }

    Optional<ExecutionScope> findScope(String tenantId, String agentId, String executionId) {
        return jdbc.query("""
                SELECT binding_revision, releases FROM sf_agent_ontology_execution
                WHERE tenant_id=? AND agent_id=? AND execution_id=?
                """, (row, index) -> new ExecutionScope(row.getLong("binding_revision"),
                decode(row.getString("releases"), RELEASES)), uuid(tenantId), uuid(agentId),
                uuid(executionId)).stream().findFirst();
    }

    void insertScope(String tenantId, String agentId, String executionId, ExecutionScope scope) {
        jdbc.update("""
                INSERT INTO sf_agent_ontology_execution (tenant_id, agent_id, execution_id, binding_revision, releases)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (tenant_id, execution_id) DO NOTHING
                """, uuid(tenantId), uuid(agentId), uuid(executionId),
                scope.bindingRevision(), encode(scope.releases()));
    }

    static UUID uuid(String value) {
        if (value == null) {
            throw new IllegalArgumentException("缺少业务标识");
        }
        String canonical = COMPACT_UUID.matcher(value).replaceFirst("$1-$2-$3-$4-$5");
        UUID uuid = UUID.fromString(canonical);
        if (!uuid.toString().equalsIgnoreCase(canonical)) {
            throw new IllegalArgumentException("业务标识格式有误");
        }
        return uuid;
    }

    private <T> T decode(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 本体绑定数据不可读取", exception);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 Agent 本体绑定", exception);
        }
    }

    record ExecutionScope(long bindingRevision, List<Release> releases) { }

    record Release(String modelId, String versionId, String checksum) { }
}
