package com.schemaplexai.service.semantic.infrastructure.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AuditLogMapper;
import com.schemaplexai.model.entity.AuditLog;
import com.schemaplexai.service.semantic.domain.model.query.QueryAuditEvent;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryAuditPort;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将语义查询审计摘要持久化到现有 sf_audit_log 表。 */
@RequiredArgsConstructor
public class MybatisSemanticQueryAuditAdapter implements SemanticQueryAuditPort {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(QueryAuditEvent event) {
        if (event == null || event.tenantId() == null || event.tenantId().isBlank()) {
            throw new IllegalArgumentException("semantic query audit tenant is required");
        }
        AuditLog log = new AuditLog();
        log.setId(event.auditId());
        log.setTenantId(event.tenantId());
        log.setUserId("unknown".equals(event.userId()) ? null : event.userId());
        log.setUsername(SecurityUtil.getCurrentUsername());
        log.setAction("semantic_query." + event.action());
        log.setResource("semantic_query");
        log.setResourceId(event.auditId());
        log.setDetail(toDetail(event));
        log.setCreatedAt(LocalDateTime.ofInstant(
                event.occurredAt() == null ? java.time.Instant.now() : event.occurredAt(), ZoneOffset.UTC));
        auditLogMapper.insertSemanticQueryAudit(log);
    }

    private String toDetail(QueryAuditEvent event) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("auditId", event.auditId());
        detail.put("planHash", event.planHash());
        detail.put("sourceId", event.sourceId());
        detail.put("outcome", event.outcome());
        detail.put("rowCount", event.rowCount());
        detail.put("elapsedMs", event.elapsedMs());
        detail.put("queryPath", event.queryPath());
        detail.put("semanticVersionId", event.semanticVersionId());
        detail.put("failureCode", event.failureCode());
        if (event.validation() != null) {
            detail.put("technicalStatus", event.validation().technicalStatus());
            detail.put("resultStatus", event.validation().resultStatus());
            detail.put("businessStatus", event.validation().businessStatus());
            detail.put("comparedRows", event.validation().comparedRows());
            detail.put("missingReferenceGroups", event.validation().missingReferenceGroups());
            detail.put("extraReferenceGroups", event.validation().extraReferenceGroups());
            detail.put("differingGroups", event.validation().differingGroups());
            if (event.validation().comparedWindow() != null) {
                detail.put("comparedWindow", Map.of(
                        "from", event.validation().comparedWindow().from(),
                        "to", event.validation().comparedWindow().to(),
                        "timeZone", event.validation().comparedWindow().timeZone()));
            }
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化语义查询审计摘要", exception);
        }
    }
}
