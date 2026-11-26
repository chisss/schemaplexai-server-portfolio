package com.schemaplexai.web.mapper;

import com.schemaplexai.model.vo.semantic.SchemaSnapshotDetailVO;
import com.schemaplexai.model.vo.semantic.SchemaSnapshotVO;
import com.schemaplexai.model.vo.semantic.SemanticPublishVO;
import com.schemaplexai.model.vo.semantic.SemanticGraphVO;
import com.schemaplexai.model.vo.semantic.SemanticModelVO;
import com.schemaplexai.model.vo.semantic.SemanticValidationVO;
import com.schemaplexai.model.vo.semantic.SemanticVersionVO;
import com.schemaplexai.service.semantic.common.SchemaSnapshotStatus;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.SemanticModel;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyEdge;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;
import com.schemaplexai.service.semantic.domain.model.ontology.SemanticValidation;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_KIND;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_SOURCE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.NAME;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.OWL_DATATYPE_PROPERTY;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.OWL_OBJECT_PROPERTY;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_FIELD;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_OBJECT;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDF_PROPERTY;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDF_TYPE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.REQUIRED;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDFS_COMMENT;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDFS_LABEL;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDFS_RANGE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SKOS_ALT_LABEL;

/** 语义领域对象到稳定 REST 契约的映射器。 */
@Component
public class SemanticWebMapper {

    private static final Set<String> NON_RELATION_PREDICATES = Set.of(
            RDF_TYPE,
            NAME,
            RDFS_LABEL,
            RDFS_COMMENT,
            SKOS_ALT_LABEL,
            RDFS_RANGE,
            REQUIRED,
            MAPPING_SOURCE,
            PHYSICAL_OBJECT,
            PHYSICAL_FIELD,
            MAPPING_KIND);

    public SemanticModelVO toModel(SemanticModel model, SemanticVersion activeVersion) {
        return new SemanticModelVO(
                model.getId(),
                model.getName(),
                model.getDomain(),
                model.getDescription(),
                lower(model.getStatus().name()),
                model.getActiveVersionId(),
                activeVersion == null ? null : activeVersion.getVersionNo(),
                0,
                activeVersion == null ? null : lower(activeVersion.getStatus().name()),
                model.getRevision(),
                null,
                null,
                model.getScopeId());
    }

    public SemanticVersionVO toVersion(SemanticVersion version) {
        return toVersion(version, null);
    }

    public SemanticVersionVO toVersion(SemanticVersion version, SemanticPublishVO.Readiness readiness) {
        return new SemanticVersionVO(
                version.getId(),
                version.getModelId(),
                version.getVersionNo(),
                lower(version.getStatus().name()),
                version.getSourceSnapshotId(),
                version.getChecksum(),
                version.getTripleCount(),
                version.getValidationReport(),
                version.getRevision(),
                version.getPublishedAt(),
                null,
                null,
                readiness);
    }

    public SemanticGraphVO toGraph(OntologySubgraph graph, String focusNodeId) {
        Map<String, NodeAttributes> attributes = new LinkedHashMap<>();
        graph.nodes().forEach(node -> attributes.put(
                node.iri(), new NodeAttributes(node.iri(), node.label())));
        graph.edges().forEach(edge -> applyMetadata(attributes.get(edge.subjectIri()), edge));

        List<SemanticGraphVO.Node> nodes = attributes.values().stream()
                .filter(attribute -> !isVocabularyResource(attribute.iri))
                .map(this::toNode)
                .toList();
        Set<String> nodeIds = nodes.stream().map(SemanticGraphVO.Node::id).collect(java.util.stream.Collectors.toSet());
        List<SemanticGraphVO.Edge> edges = graph.edges().stream()
                .filter(edge -> edge.object().kind() == OntologyTerm.Kind.IRI)
                .filter(edge -> nodeIds.contains(edge.subjectIri()) && nodeIds.contains(edge.object().value()))
                .filter(edge -> !NON_RELATION_PREDICATES.contains(edge.predicateIri())
                        || (RDFS_RANGE.equals(edge.predicateIri()) && attributes.get(edge.subjectIri()).objectProperty))
                .map(this::toEdge)
                .toList();
        return new SemanticGraphVO(nodes, edges, nodes.size(), graph.hasMore(), focusNodeId);
    }

    public SemanticValidationVO toValidation(
            String versionId,
            long revision,
            SemanticValidation validation) {
        return new SemanticValidationVO(
                validation.valid(),
                versionId,
                revision,
                issues(validation.report()),
                validation.validatedAt());
    }

    public SchemaSnapshotVO toSnapshot(SchemaSnapshot snapshot, boolean driftDetected) {
        int objectCount = snapshot.getSchema().objects().size();
        int fieldCount = snapshot.getSchema().objects().stream()
                .mapToInt(object -> object.fields().size())
                .sum();
        return new SchemaSnapshotVO(
                snapshot.getId(),
                snapshot.getSourceId(),
                null,
                snapshot.getSchema().databaseType(),
                snapshot.getFingerprint(),
                snapshotStatus(snapshot),
                objectCount,
                fieldCount,
                driftDetected,
                snapshot.getSchema().warnings().size(),
                null,
                snapshot.getScannedAt(),
                snapshot.getScannedAt());
    }

    public SchemaSnapshotDetailVO toSnapshotDetail(SchemaSnapshot snapshot) {
        var schema = snapshot.getSchema();
        return new SchemaSnapshotDetailVO(snapshot.getId(), snapshot.getSourceId(), snapshotStatus(snapshot), schema.databaseType(),
                new SchemaSnapshotDetailVO.Schema(schema.catalog(), schema.schema(),
                        schema.objects().stream().map(this::toSchemaObject).toList()));
    }

    private SchemaSnapshotDetailVO.ObjectSchema toSchemaObject(SchemaObject object) {
        var fields = object.fields().stream()
                .map(field -> new SchemaSnapshotDetailVO.Field(field.name(), field.dataType(), field.nullable()))
                .toList();
        var foreignKeys = object.foreignKeys().stream()
                .map(key -> new SchemaSnapshotDetailVO.ForeignKey(key.name(), key.columns(), key.referencedSchema(),
                        key.referencedObject(), key.referencedColumns()))
                .toList();
        return new SchemaSnapshotDetailVO.ObjectSchema(object.key(), object.namespace(), object.name(),
                object.type().name(), fields, object.primaryKey(), foreignKeys);
    }

    private String snapshotStatus(SchemaSnapshot snapshot) {
        return snapshot.getStatus() == SchemaSnapshotStatus.READY ? "completed" : lower(snapshot.getStatus().name());
    }

    private void applyMetadata(NodeAttributes attributes, OntologyEdge edge) {
        if (attributes == null) {
            return;
        }
        String value = edge.object().value();
        switch (edge.predicateIri()) {
            case NAME -> attributes.name = value;
            case RDFS_LABEL -> attributes.label = value;
            case RDFS_COMMENT -> attributes.description = value;
            case SKOS_ALT_LABEL -> attributes.synonyms.add(value);
            case RDFS_RANGE -> attributes.dataType = compactDatatype(value);
            case REQUIRED -> attributes.required = Boolean.valueOf(value);
            case MAPPING_SOURCE -> attributes.mapping.put("sourceId", value);
            case PHYSICAL_OBJECT -> attributes.mapping.put("physicalObject", value);
            case PHYSICAL_FIELD -> attributes.mapping.put("physicalField", value);
            case MAPPING_KIND -> attributes.mapping.put("mappingKind", value);
            case RDF_TYPE -> {
                attributes.objectProperty |= OWL_OBJECT_PROPERTY.equals(value);
                attributes.property |= RDF_PROPERTY.equals(value) || OWL_DATATYPE_PROPERTY.equals(value)
                        || attributes.objectProperty;
            }
            default -> {
            }
        }
    }

    private SemanticGraphVO.Node toNode(NodeAttributes attributes) {
        String name = attributes.name == null ? localName(attributes.iri) : attributes.name;
        String label = attributes.label == null ? name : attributes.label;
        SemanticGraphVO.Mapping mapping = null;
        if (attributes.mapping.containsKey("sourceId") && attributes.mapping.containsKey("physicalObject")) {
            mapping = new SemanticGraphVO.Mapping(
                    null,
                    attributes.mapping.get("sourceId"),
                    attributes.iri,
                    attributes.mapping.get("physicalObject"),
                    attributes.mapping.get("physicalField"),
                    attributes.mapping.getOrDefault("mappingKind", "column"));
        }
        return new SemanticGraphVO.Node(
                attributes.iri,
                attributes.iri,
                name,
                label,
                attributes.description,
                attributes.property ? "property" : "concept",
                List.copyOf(attributes.synonyms),
                attributes.objectProperty ? null : attributes.dataType,
                attributes.required,
                mapping);
    }

    private SemanticGraphVO.Edge toEdge(OntologyEdge edge) {
        String target = edge.object().value();
        String id = Integer.toHexString((edge.subjectIri() + edge.predicateIri() + target).hashCode());
        return new SemanticGraphVO.Edge(
                id,
                edge.subjectIri(),
                target,
                edge.predicateIri(),
                localName(edge.predicateIri()),
                false);
    }

    private List<SemanticValidationVO.Issue> issues(String report) {
        if (report == null || report.isBlank()) {
            return List.of();
        }
        List<SemanticValidationVO.Issue> result = new ArrayList<>();
        for (String message : report.split(";\\s*")) {
            String iri = extractIri(message);
            result.add(new SemanticValidationVO.Issue(
                    "SHACL_VIOLATION",
                    "error",
                    message,
                    iri,
                    iri,
                    null));
        }
        return result;
    }

    private String extractIri(String message) {
        int start = message.indexOf('<');
        int end = message.indexOf('>', start + 1);
        return start >= 0 && end > start ? message.substring(start + 1, end) : null;
    }

    private String compactDatatype(String value) {
        String prefix = "http://www.w3.org/2001/XMLSchema#";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private boolean isVocabularyResource(String iri) {
        return iri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
                || iri.startsWith("http://www.w3.org/2000/01/rdf-schema#")
                || iri.startsWith("http://www.w3.org/2001/XMLSchema#")
                || iri.startsWith("http://www.w3.org/2002/07/owl#")
                || iri.startsWith("http://www.w3.org/ns/shacl#");
    }

    private String localName(String iri) {
        int separator = Math.max(iri.lastIndexOf('#'), Math.max(iri.lastIndexOf('/'), iri.lastIndexOf(':')));
        return separator >= 0 && separator + 1 < iri.length() ? iri.substring(separator + 1) : iri;
    }

    private String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static final class NodeAttributes {

        private final String iri;
        private String name;
        private String label;
        private String description;
        private final List<String> synonyms = new ArrayList<>();
        private String dataType;
        private Boolean required;
        private boolean property;
        private boolean objectProperty;
        private final Map<String, String> mapping = new HashMap<>();

        private NodeAttributes(String iri, String label) {
            this.iri = iri;
            this.label = label;
        }
    }
}
