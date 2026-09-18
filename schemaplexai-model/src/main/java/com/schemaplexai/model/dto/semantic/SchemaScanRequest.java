package com.schemaplexai.model.dto.semantic;

import jakarta.validation.constraints.Size;

import java.util.Set;

/** 数据源 Schema 扫描范围。 */
public record SchemaScanRequest(
        @Size(max = 200, message = "catalog不能超过200个字符") String catalog,
        @Size(max = 200, message = "schema不能超过200个字符") String schema,
        @Size(max = 500, message = "扫描对象不能超过500个") Set<String> includeObjects) {
}
