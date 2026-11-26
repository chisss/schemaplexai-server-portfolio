package com.schemaplexai.service.semantic.domain.port;

import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;

import java.util.List;

/** 从人工目标与已选择的 Schema 元数据生成候选配置。 */
public interface OntologySuggestionPort {

    Suggestion generate(String tenantId, Request request);

    record Request(String goal, OntologyConfiguration configuration, List<SelectedObject> selectedSchema) { }

    record SelectedObject(String key, String type, List<Column> columns,
                          List<String> primaryKey, List<ForeignKey> foreignKeys) { }

    record Column(String name, String dataType, boolean nullable) { }

    record ForeignKey(List<String> columns, String referencedObject, List<String> referencedColumns) { }

    record Suggestion(OntologyConfiguration configuration, String summary, List<String> assumptions) { }
}
