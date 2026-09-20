package com.schemaplexai.service.semantic.domain.model.query;

import java.time.Instant;

/** 语义查询审计摘要，不包含密码和原始敏感值。 */
public record QueryAuditEvent(
        String auditId,
        String tenantId,
        String userId,
        String planHash,
        String sourceId,
        String action,
        String outcome,
        int rowCount,
        long elapsedMs,
        Instant occurredAt) {
}
