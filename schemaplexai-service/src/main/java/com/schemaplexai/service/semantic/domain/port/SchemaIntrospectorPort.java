package com.schemaplexai.service.semantic.domain.port;

import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaScanScope;

/** 数据库类型专属的结构扫描端口。 */
public interface SchemaIntrospectorPort {

    boolean supports(String databaseType);

    DatabaseSchema scan(SchemaScanScope scope, SchemaMetadataSession session);
}
