package com.schemaplexai.service.semantic.domain.port;

import com.schemaplexai.service.semantic.domain.model.query.QueryAuditEvent;

/** 语义查询审计写入端口。 */
public interface SemanticQueryAuditPort {

    void record(QueryAuditEvent event);
}
