package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.semantic.domain.port.SemanticQuerySourcePort;

/** 将当前租户限定的数据源服务适配为查询编译所需的最小元信息端口。 */
public final class DatabaseSemanticQuerySourceAdapter implements SemanticQuerySourcePort {

    private final DatabaseSourceService databaseSourceService;

    public DatabaseSemanticQuerySourceAdapter(DatabaseSourceService databaseSourceService) {
        this.databaseSourceService = databaseSourceService;
    }

    @Override
    public String databaseType(String sourceId) {
        var source = databaseSourceService.getById(sourceId);
        if (source == null || source.getDatabaseType() == null || source.getDatabaseType().isBlank()) {
            throw new IllegalArgumentException("database source type is unavailable");
        }
        return source.getDatabaseType();
    }
}
