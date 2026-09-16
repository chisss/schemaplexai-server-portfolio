package com.schemaplexai.service.semantic.domain.port;

import java.util.List;
import java.util.Map;

/** 受限元数据读取会话；实现不得返回连接凭据。 */
public interface SchemaMetadataSession extends AutoCloseable {

    String sourceId();

    String databaseType();

    String defaultCatalog();

    String defaultSchema();

    List<Map<String, Object>> query(String statement);

    List<Map<String, Object>> invoke(String operation, Map<String, Object> arguments);

    @Override
    default void close() {
    }
}
