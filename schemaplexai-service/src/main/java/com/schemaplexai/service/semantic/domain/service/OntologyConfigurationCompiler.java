package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Concept;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Property;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Relationship;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_KIND;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_SOURCE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.NAME;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.OWL_CLASS;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.OWL_DATATYPE_PROPERTY;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.OWL_OBJECT_PROPERTY;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_FIELD;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_OBJECT;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDF_TYPE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDFS_COMMENT;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDFS_DOMAIN;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDFS_LABEL;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDFS_RANGE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDFS_SUBCLASS_OF;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.REQUIRED;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SHACL_DATATYPE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SHACL_MIN_COUNT;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SHACL_NODE_SHAPE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SHACL_PATH;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SHACL_PROPERTY;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SHACL_TARGET_CLASS;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.XSD_BOOLEAN;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.XSD_INTEGER;

/** 将本体表单编译为只使用服务端受控词汇的 RDF 和 SHACL。 */
public final class OntologyConfigurationCompiler {

    private static final Pattern STABLE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private static final Set<String> DATA_TYPES = Set.of("string", "integer", "decimal", "boolean", "date", "dateTime");
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    public CompiledOntology compile(
            OntologyGraphRef ref, OntologyConfiguration configuration, SchemaSnapshot snapshot) {
        if (configuration == null) {
            throw new IllegalArgumentException("本体配置不能为空");
        }
        Map<String, SchemaObject> objects = new HashMap<>();
        if (snapshot != null) {
            snapshot.getSchema().objects().forEach(object -> objects.put(object.key(), object));
        }
        var concepts = concepts(configuration.classes(), objects);
        var properties = properties(configuration.properties(), concepts, objects);
        var relationships = relationships(configuration.relationships(), concepts);
        var normalized = new OntologyConfiguration(List.copyOf(concepts.values()), properties, relationships,
                configuration.queryProfile());

        String prefix = "urn:spx:tenant:" + ref.tenantId() + ":semantic:" + ref.modelId();
        List<OntologyStatement> assertions = new ArrayList<>();
        List<OntologyStatement> shapes = new ArrayList<>();
        for (Concept concept : normalized.classes()) {
            String iri = conceptIri(prefix, concept.id());
            resource(assertions, iri, OWL_CLASS, concept.name(), concept.label());
            if (concept.description() != null) {
                addLiteral(assertions, iri, RDFS_COMMENT, concept.description());
            }
            if (concept.sourceObject() != null) {
                addMapping(assertions, iri, snapshot, objects.get(concept.sourceObject()), null);
            }
        }
        Set<String> shapedClasses = new HashSet<>();
        for (Property property : properties) {
            addProperty(assertions, prefix, property);
            addPropertyShape(shapes, prefix, property, shapedClasses);
            Concept owner = concepts.get(property.classId());
            if (property.sourceField() != null) {
                addMapping(assertions, propertyIri(prefix, property.id()), snapshot,
                        objects.get(owner.sourceObject()), property.sourceField());
            }
        }
        for (Relationship relationship : relationships) {
            addRelationship(assertions, prefix, relationship);
        }
        return new CompiledOntology(normalized, List.copyOf(assertions), List.copyOf(shapes));
    }

    private Map<String, Concept> concepts(List<Concept> input, Map<String, SchemaObject> objects) {
        Map<String, Concept> concepts = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (Concept item : requireItems(input, 200, "概念")) {
            String id = identifier(item.id());
            String name = text(item.name(), 100, "概念技术名");
            String sourceObject = optional(item.sourceObject(), 512, "来源对象");
            unique(names, name.toLowerCase(Locale.ROOT), "概念技术名重复");
            if (sourceObject != null && !objects.containsKey(sourceObject)) {
                throw new IllegalArgumentException("概念来源对象不在当前版本的 Schema 快照中");
            }
            Concept concept = new Concept(id, name, text(item.label(), 200, "概念标签"),
                    optional(item.description(), 2000, "概念描述"), sourceObject);
            if (concepts.putIfAbsent(id, concept) != null) {
                throw new IllegalArgumentException("概念标识重复");
            }
        }
        return concepts;
    }

    private List<Property> properties(
            List<Property> input, Map<String, Concept> concepts, Map<String, SchemaObject> objects) {
        Set<String> ids = new HashSet<>();
        Set<List<String>> names = new HashSet<>();
        List<Property> properties = new ArrayList<>();
        for (Property item : requireItems(input, 1000, "属性")) {
            String id = identifier(item.id());
            String name = text(item.name(), 100, "属性技术名");
            Concept owner = requireConcept(concepts, item.classId());
            String field = optional(item.sourceField(), 512, "来源字段");
            String dataType = requireOption(item.dataType(), DATA_TYPES, "不支持的属性类型");
            unique(ids, id, "属性标识重复");
            unique(names, List.of(owner.id(), name.toLowerCase(Locale.ROOT)), "同一概念的属性技术名重复");
            if (field != null && !containsField(objects.get(owner.sourceObject()), field)) {
                throw new IllegalArgumentException("属性来源字段不在所属概念绑定的对象中");
            }
            properties.add(new Property(id, owner.id(), name, text(item.label(), 200, "属性标签"),
                    dataType, item.required(), field));
        }
        return List.copyOf(properties);
    }

    private List<Relationship> relationships(List<Relationship> input, Map<String, Concept> concepts) {
        Set<String> ids = new HashSet<>();
        Set<List<String>> names = new HashSet<>();
        List<Relationship> relationships = new ArrayList<>();
        Map<String, Set<String>> parents = new HashMap<>();
        for (Relationship item : requireItems(input, 500, "关系")) {
            String id = identifier(item.id());
            String name = text(item.name(), 100, "关系技术名");
            Concept origin = requireConcept(concepts, item.fromClassId());
            Concept target = requireConcept(concepts, item.toClassId());
            String kind = requireOption(item.kind(), Set.of("RELATION", "SUBCLASS"), "不支持的关系类型");
            unique(ids, id, "关系标识重复");
            unique(names, List.of(origin.id(), kind, name.toLowerCase(Locale.ROOT)), "同一起点的关系技术名重复");
            if ("SUBCLASS".equals(kind)) {
                unique(parents.computeIfAbsent(origin.id(), ignored -> new HashSet<>()),
                        target.id(), "继承关系重复");
            }
            relationships.add(new Relationship(id, origin.id(), target.id(), name,
                    text(item.label(), 200, "关系标签"), kind));
        }
        Set<String> checked = new HashSet<>();
        for (String node : parents.keySet()) {
            checkInheritance(node, parents, checked, new HashSet<>());
        }
        return List.copyOf(relationships);
    }

    private void checkInheritance(
            String node, Map<String, Set<String>> parents, Set<String> checked, Set<String> visiting) {
        if (checked.contains(node)) {
            return;
        }
        if (!visiting.add(node)) {
            throw new IllegalArgumentException("继承关系不能形成环");
        }
        for (String parent : parents.getOrDefault(node, Set.of())) {
            checkInheritance(parent, parents, checked, visiting);
        }
        visiting.remove(node);
        checked.add(node);
    }

    private boolean containsField(SchemaObject object, String field) {
        return object != null && object.fields().stream().anyMatch(candidate -> candidate.name().equals(field));
    }

    private Concept requireConcept(Map<String, Concept> concepts, String id) {
        Concept concept = concepts.get(id);
        if (concept == null) {
            throw new IllegalArgumentException("属性或关系引用了不存在的概念");
        }
        return concept;
    }

    private String requireOption(String value, Set<String> choices, String message) {
        if (value == null || !choices.contains(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private <T> List<T> requireItems(List<T> items, int limit, String field) {
        if (items == null || items.size() > limit || items.stream().anyMatch(item -> item == null)) {
            throw new IllegalArgumentException(field + "列表无效，最多可配置 " + limit + " 项");
        }
        return items;
    }

    private <T> void unique(Set<T> values, T value, String message) {
        if (!values.add(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String identifier(String value) {
        if (value == null || !STABLE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("概念、属性和关系必须使用有效的稳定标识");
        }
        return value;
    }

    private String text(String value, int limit, String field) {
        String normalized = optional(value, limit, field);
        if (normalized == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return normalized;
    }

    private String optional(String value, int limit, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > limit) {
            throw new IllegalArgumentException(field + "不能超过 " + limit + " 个字符");
        }
        return value.trim();
    }

    private String conceptIri(String prefix, String id) {
        return prefix + ":concept:" + id;
    }

    private String propertyIri(String prefix, String id) {
        return prefix + ":property:" + id;
    }

    private void addProperty(List<OntologyStatement> assertions, String prefix, Property property) {
        String iri = propertyIri(prefix, property.id());
        resource(assertions, iri, OWL_DATATYPE_PROPERTY, property.name(), property.label());
        addIri(assertions, iri, RDFS_DOMAIN, conceptIri(prefix, property.classId()));
        addIri(assertions, iri, RDFS_RANGE, XSD + property.dataType());
        assertions.add(new OntologyStatement(
                iri, REQUIRED, OntologyTerm.typedLiteral(Boolean.toString(property.required()), XSD_BOOLEAN)));
    }

    private void addPropertyShape(
            List<OntologyStatement> shapes, String prefix, Property property, Set<String> shapedClasses) {
        String nodeShape = prefix + ":shape:class:" + property.classId();
        if (shapedClasses.add(property.classId())) {
            addIri(shapes, nodeShape, RDF_TYPE, SHACL_NODE_SHAPE);
            addIri(shapes, nodeShape, SHACL_TARGET_CLASS, conceptIri(prefix, property.classId()));
        }
        String propertyShape = prefix + ":shape:property:" + property.id();
        addIri(shapes, nodeShape, SHACL_PROPERTY, propertyShape);
        addIri(shapes, propertyShape, SHACL_PATH, propertyIri(prefix, property.id()));
        addIri(shapes, propertyShape, SHACL_DATATYPE, XSD + property.dataType());
        if (property.required()) {
            shapes.add(new OntologyStatement(propertyShape, SHACL_MIN_COUNT, OntologyTerm.typedLiteral("1", XSD_INTEGER)));
        }
    }

    private void addRelationship(List<OntologyStatement> assertions, String prefix, Relationship relationship) {
        String origin = conceptIri(prefix, relationship.fromClassId());
        String target = conceptIri(prefix, relationship.toClassId());
        if ("SUBCLASS".equals(relationship.kind())) {
            addIri(assertions, origin, RDFS_SUBCLASS_OF, target);
            return;
        }
        String iri = prefix + ":relationship:" + relationship.id();
        resource(assertions, iri, OWL_OBJECT_PROPERTY, relationship.name(), relationship.label());
        addIri(assertions, iri, RDFS_DOMAIN, origin);
        addIri(assertions, iri, RDFS_RANGE, target);
    }

    private void addMapping(
            List<OntologyStatement> assertions, String iri, SchemaSnapshot snapshot, SchemaObject object, String field) {
        String physicalObject = object.namespace() == null ? object.name() : object.namespace() + "." + object.name();
        addLiteral(assertions, iri, MAPPING_SOURCE, snapshot.getSourceId());
        addLiteral(assertions, iri, PHYSICAL_OBJECT, physicalObject);
        addLiteral(assertions, iri, MAPPING_KIND, field == null ? "object" : "column");
        if (field != null) {
            addLiteral(assertions, iri, PHYSICAL_FIELD, field);
        }
    }

    private void resource(List<OntologyStatement> assertions, String subject, String type, String name, String label) {
        addIri(assertions, subject, RDF_TYPE, type);
        addLiteral(assertions, subject, NAME, name);
        addLiteral(assertions, subject, RDFS_LABEL, label);
    }

    private void addIri(List<OntologyStatement> statements, String subject, String predicate, String object) {
        statements.add(new OntologyStatement(subject, predicate, OntologyTerm.iri(object)));
    }

    private void addLiteral(List<OntologyStatement> statements, String subject, String predicate, String literal) {
        statements.add(new OntologyStatement(subject, predicate, OntologyTerm.literal(literal)));
    }

    public record CompiledOntology(
            OntologyConfiguration configuration, List<OntologyStatement> assertions, List<OntologyStatement> shapes) {
    }
}
