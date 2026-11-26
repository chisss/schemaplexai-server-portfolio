package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Concept;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Property;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationWorkspace;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyQueryProfile;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaForeignKey;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.port.OntologySuggestionPort;
import com.schemaplexai.service.semantic.domain.port.OntologySuggestionPort.Column;
import com.schemaplexai.service.semantic.domain.port.OntologySuggestionPort.ForeignKey;
import com.schemaplexai.service.semantic.domain.port.OntologySuggestionPort.SelectedObject;
import com.schemaplexai.service.semantic.domain.port.OntologySuggestionPort.Suggestion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 按当前草稿与用户选择生成建议，写入与发布仍由各自入口处理。 */
@Service
@RequiredArgsConstructor
public class OntologySuggestionApplicationService {

    private final OntologyConfigurationApplicationService configurationService;
    private final SchemaScanApplicationService schemaService;
    private final OntologySuggestionPort assistant;

    public Suggestion suggest(String versionId, long expectedRevision, String goal, List<String> selectedObjects) {
        OntologyConfigurationWorkspace current = configurationService.load(versionId);
        if (current.revision() != expectedRevision) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        if (!current.editable()) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID);
        }

        Set<String> selection = new HashSet<>(selectedObjects);
        if (selection.size() != selectedObjects.size()) {
            throw invalidSelection("所选对象重复，请重新选择");
        }
        List<SelectedObject> schema = selectedSchema(current, selection);
        var prior = current.configuration() == null ? OntologyConfiguration.empty() : current.configuration();
        HiddenBindings hidden = hiddenBindings(prior, selection);
        Suggestion suggestion = assistant.generate(SecurityUtil.getCurrentTenantId(),
                new OntologySuggestionPort.Request(goal.trim(), hideBindings(prior, hidden), schema));

        var candidate = suggestion.configuration();
        verifySelectedBindings(hidden, candidate, selection);
        var restored = restoreBindings(candidate, hidden, prior.queryProfile());
        configurationService.verifyCandidate(versionId, expectedRevision, restored);
        return new Suggestion(restored, suggestion.summary(), suggestion.assumptions());
    }

    private List<SelectedObject> selectedSchema(OntologyConfigurationWorkspace current, Set<String> selection) {
        if (current.sourceSnapshotId() == null) {
            if (!selection.isEmpty()) {
                throw invalidSelection("空白草稿无法使用数据源对象");
            }
            return List.of();
        }
        if (selection.isEmpty()) {
            throw invalidSelection("请选择需要 Agent 参考的数据源对象");
        }
        List<SchemaObject> objects = schemaService.getSnapshot(current.sourceId(), current.sourceSnapshotId())
                .getSchema().objects().stream()
                .filter(object -> selection.contains(object.key()))
                .toList();
        if (objects.size() != selection.size()) {
            throw invalidSelection("所选对象不属于草稿绑定的 Schema 快照");
        }
        return objects.stream().map(object -> toSelectedObject(object, selection)).toList();
    }

    private SelectedObject toSelectedObject(SchemaObject object, Set<String> selection) {
        List<Column> columns = object.fields().stream()
                .map(field -> new Column(field.name(), field.dataType(), field.nullable()))
                .toList();
        List<ForeignKey> foreignKeys = object.foreignKeys().stream()
                .filter(foreignKey -> foreignKey.referencedObject() != null)
                .map(foreignKey -> new ForeignKey(foreignKey.columns(), referenceKey(object, foreignKey),
                        foreignKey.referencedColumns()))
                .filter(foreignKey -> selection.contains(foreignKey.referencedObject()))
                .toList();
        return new SelectedObject(object.key(), object.type().name(), columns, object.primaryKey(), foreignKeys);
    }

    private String referenceKey(SchemaObject object, SchemaForeignKey foreignKey) {
        String namespace = Objects.requireNonNullElse(foreignKey.referencedSchema(),
                Objects.toString(object.namespace(), ""));
        return namespace.isEmpty() ? foreignKey.referencedObject() : namespace + "." + foreignKey.referencedObject();
    }

    private HiddenBindings hiddenBindings(OntologyConfiguration prior, Set<String> selection) {
        var concepts = prior.classes().stream()
                .filter(item -> item.sourceObject() != null && !selection.contains(item.sourceObject()))
                .collect(Collectors.toMap(Concept::id, Concept::sourceObject));
        var properties = prior.properties().stream()
                .filter(item -> item.sourceField() != null && concepts.containsKey(item.classId()))
                .collect(Collectors.toMap(Property::id, Function.identity()));
        return new HiddenBindings(concepts, properties);
    }

    private OntologyConfiguration hideBindings(OntologyConfiguration prior, HiddenBindings hidden) {
        return new OntologyConfiguration(
                prior.classes().stream().map(item -> hidden.concepts().containsKey(item.id())
                        ? withObject(item, null) : item).toList(),
                prior.properties().stream().map(item -> hidden.properties().containsKey(item.id())
                        ? withField(item, null) : item).toList(), prior.relationships());
    }

    private void verifySelectedBindings(HiddenBindings hidden, OntologyConfiguration candidate, Set<String> selection) {
        if (candidate.classes() == null || candidate.properties() == null || candidate.relationships() == null
                || candidate.classes().stream().anyMatch(Objects::isNull)
                || candidate.properties().stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ResultCode.AGENT_CONFIG_ERROR, "Agent 返回的本体建议格式有误，请重新生成");
        }
        boolean newOutsideSelection = candidate.classes().stream()
                .anyMatch(item -> item.sourceObject() != null && !selection.contains(item.sourceObject()));
        boolean changedHiddenMapping = candidate.classes().stream()
                .anyMatch(item -> item.sourceObject() != null && hidden.concepts().containsKey(item.id()));
        boolean newHiddenField = candidate.properties().stream()
                .anyMatch(item -> item.sourceField() != null && hidden.concepts().containsKey(item.classId()));
        if (newOutsideSelection || changedHiddenMapping || newHiddenField) {
            throw invalidSuggestionMapping();
        }
    }

    private OntologyConfiguration restoreBindings(OntologyConfiguration candidate, HiddenBindings hidden,
                                                  OntologyQueryProfile profile) {
        var concepts = candidate.classes().stream().map(item -> hidden.concepts().containsKey(item.id())
                ? withObject(item, hidden.concepts().get(item.id())) : item).toList();
        var properties = candidate.properties().stream().map(item -> restoreField(item, hidden)).toList();
        return new OntologyConfiguration(concepts, properties, candidate.relationships(), profile);
    }

    private Property restoreField(Property item, HiddenBindings hidden) {
        Property original = hidden.properties().get(item.id());
        return original != null && original.classId().equals(item.classId())
                ? withField(item, original.sourceField()) : item;
    }

    private Concept withObject(Concept item, String sourceObject) {
        return new Concept(item.id(), item.name(), item.label(), item.description(), sourceObject);
    }

    private Property withField(Property item, String sourceField) {
        return new Property(item.id(), item.classId(), item.name(), item.label(), item.dataType(), item.required(), sourceField);
    }

    private BusinessException invalidSelection(String message) {
        return new BusinessException(ResultCode.BAD_REQUEST, message);
    }

    private BusinessException invalidSuggestionMapping() {
        return new BusinessException(ResultCode.AGENT_CONFIG_ERROR, "Agent 建议引用了本次未选中的 Schema 对象或字段，请重新生成");
    }

    private record HiddenBindings(Map<String, String> concepts, Map<String, Property> properties) { }
}
