package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.service.semantic.domain.model.query.QueryAuditEvent;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryAuditPort;
import lombok.extern.slf4j.Slf4j;

/** 先以结构化日志承载查询审计摘要，后续可替换为审计表或事件流适配器。 */
@Slf4j
public final class LoggingSemanticQueryAuditAdapter implements SemanticQueryAuditPort {

    @Override
    public void record(QueryAuditEvent event) {
        log.info("semantic query audit: auditId={}, tenantId={}, userId={}, planHash={}, sourceId={}, action={}, outcome={}, rowCount={}, elapsedMs={}",
                event.auditId(), event.tenantId(), event.userId(), event.planHash(), event.sourceId(),
                event.action(), event.outcome(), event.rowCount(), event.elapsedMs());
    }
}
