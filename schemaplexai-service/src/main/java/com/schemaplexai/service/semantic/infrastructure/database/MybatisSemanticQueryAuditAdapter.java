package com.schemaplexai.service.semantic.infrastructure.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AuditLogMapper;
import com.schemaplexai.model.entity.AuditLog;
import com.schemaplexai.service.semantic.domain.model.query.QueryAuditEvent;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryAuditPort;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将语义查询审计摘要持久化到现有 sf_audit_log 表。 */
@RequiredArgsConstructor
public final class MybatisSemanticQueryAuditAdapter implements SemanticQueryAuditPort {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void record(QueryAuditEvent event) {
        if (event == null || event.tenantId() == null || event.tenantId().isBlank()) {
            throw new IllegalArgumentException("semantic query audit tenant is required");
        }
        AuditLog log = new AuditLog();
        log.setTenantId(event.tenantId());
        log.setUserId(event.userId());
        log.setUsername(SecurityUtil.getCurrentUsername());
        log.setAction("semantic_query." + event.action());
        log.setResource("semantic_query");
        log.setResourceId(event.planHash());
        log.setDetail(toDetail(event));
        log.setCreatedAt(LocalDateTime.ofInstant(
                event.occurredAt() == null ? java.time.Instant.now() : event.occurredAt(), ZoneOffset.UTC));
        auditLogMapper.insert(log);
    }

    private String toDetail(QueryAuditEvent event) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("auditId", event.auditId());
        detail.put("sourceId", event.sourceId());
        detail.put("outcome", event.outcome());
        detail.put("rowCount", event.rowCount());
        detail.put("elapsedMs", event.elapsedMs());
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化语义查询审计摘要", exception);
        }
    }
}
