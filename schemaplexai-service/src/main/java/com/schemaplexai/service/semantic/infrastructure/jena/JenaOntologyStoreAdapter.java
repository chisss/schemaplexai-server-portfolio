package com.schemaplexai.service.semantic.infrastructure.jena;

import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyEdge;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyNode;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyNodeUpdate;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_KIND;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_SOURCE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.NAME;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_FIELD;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_OBJECT;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.REQUIRED;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDFS_COMMENT;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDFS_RANGE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SKOS_ALT_LABEL;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.XSD_BOOLEAN;

/** Jena/TDB2 本体图适配器，提供结构化写入和受限邻域读取。 */
@Component
public class JenaOntologyStoreAdapter implements OntologyStorePort {

    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema#";
    private static final Set<String> EDITABLE_PREDICATES = Set.of(
            NAME,
            RDFS_LABEL,
            SKOS_PREF_LABEL,
            RDFS_COMMENT,
            SKOS_ALT_LABEL,
            RDFS_RANGE,
            REQUIRED,
            MAPPING_SOURCE,
            PHYSICAL_OBJECT,
            PHYSICAL_FIELD,
            MAPPING_KIND);

    private final SemanticDatasetManager datasetManager;
    private final TenantDatasetViewFactory viewFactory;
    private final SemanticGraphIriFactory graphIriFactory;
    private final SemanticStoreProperties properties;
    private final JenaOntologyStatementMapper statementMapper;

    public JenaOntologyStoreAdapter(
            SemanticDatasetManager datasetManager,
            TenantDatasetViewFactory viewFactory,
            SemanticGraphIriFactory graphIriFactory,
            SemanticStoreProperties properties,
            JenaOntologyStatementMapper statementMapper) {
        this.datasetManager = datasetManager;
        this.viewFactory = viewFactory;
        this.graphIriFactory = graphIriFactory;
        this.properties = properties;
        this.statementMapper = statementMapper;
    }

    @Override
    public void replaceDraft(OntologyGraph graph) {
        if (graph == null) {
            throw new IllegalArgumentException("graph is required");
        }
        OntologyGraphRef ref = graph.ref();
        SemanticGraphIriFactory.GraphSet graphSet = graphIriFactory.create(
                ref.tenantId(), ref.modelId(), ref.version());
        if (graph.statements().size() > properties.getMaxAssertedTriples()) {
            throw new IllegalArgumentException("asserted graph exceeds configured triple quota");
        }
        Node graphNode = NodeFactory.createURI(graphSet.asserted());
        datasetManager.write(dataset -> {
            dataset.deleteAny(graphNode, Node.ANY, Node.ANY, Node.ANY);
            for (OntologyStatement statement : graph.statements()) {
                dataset.add(statementMapper.toQuad(graphNode, statement));
            }
        });
    }

    @Override
    public void updateNode(OntologyGraphRef ref, String nodeIri, OntologyNodeUpdate update) {
        if (ref == null || update == null) {
            throw new IllegalArgumentException("ref and update are required");
        }
        Node subject = absoluteIri(nodeIri, "nodeIri");
        SemanticGraphIriFactory.GraphSet graphSet = graphIriFactory.create(
                ref.tenantId(), ref.modelId(), ref.version());
        Node graphNode = NodeFactory.createURI(graphSet.asserted());
        datasetManager.write(dataset -> {
            if (!dataset.find(graphNode, subject, Node.ANY, Node.ANY).hasNext()) {
                throw new IllegalArgumentException("ontology node not found");
            }
            EDITABLE_PREDICATES.forEach(predicate -> dataset.deleteAny(
                    graphNode, subject, NodeFactory.createURI(predicate), Node.ANY));
            addLiteral(dataset, graphNode, subject, NAME, update.name(), null);
            addLiteral(dataset, graphNode, subject, RDFS_LABEL,
                    update.label() == null ? update.name() : update.label(), null);
            addOptionalLiteral(dataset, graphNode, subject, RDFS_COMMENT, update.description());
            update.synonyms().forEach(value -> addLiteral(
                    dataset, graphNode, subject, SKOS_ALT_LABEL, value, null));
            if (update.dataType() != null) {
                dataset.add(new Quad(graphNode, subject, NodeFactory.createURI(RDFS_RANGE),
                        absoluteIri(normalizeDatatype(update.dataType()), "dataType")));
            }
            if (update.required() != null) {
                addLiteral(dataset, graphNode, subject, REQUIRED,
                        update.required().toString(), XSD_BOOLEAN);
            }
            addMapping(dataset, graphNode, subject, update.mapping());
            if (countGraph(dataset, graphNode) > properties.getMaxAssertedTriples()) {
                throw new IllegalArgumentException("asserted graph exceeds configured triple quota");
            }
        });
    }

    @Override
    public OntologySubgraph querySubgraph(OntologyGraphRef ref, GraphQuery query) {
        if (ref == null) {
            throw new IllegalArgumentException("ref is required");
        }
        GraphQuery safeQuery = query == null ? GraphQuery.firstPage() : query;
        if (properties.getMaxGraphNodes() < 1 || properties.getMaxGraphNodes() > 500) {
            throw new IllegalStateException("maxGraphNodes must be between 1 and 500");
        }
        if (safeQuery.size() > properties.getMaxGraphNodes()) {
            throw new IllegalArgumentException("query size exceeds configured node page quota");
        }
        SemanticGraphIriFactory.GraphSet graphSet = graphIriFactory.create(
                ref.tenantId(), ref.modelId(), ref.version());
        return datasetManager.read(dataset -> readVisibleGraph(dataset, ref, graphSet, safeQuery));
    }

    private OntologySubgraph readVisibleGraph(
            DatasetGraph source,
            OntologyGraphRef ref,
            SemanticGraphIriFactory.GraphSet graphSet,
            GraphQuery query) {
        Dataset view = viewFactory.createReadOnlyView(
                source, ref.tenantId(), ref.modelId(), ref.version());
        Node asserted = NodeFactory.createURI(graphSet.asserted());
        List<OntologyStatement> statements = new ArrayList<>();
        Iterator<Quad> quads = view.asDatasetGraph().find(asserted, Node.ANY, Node.ANY, Node.ANY);
        quads.forEachRemaining(quad -> statements.add(statementMapper.fromQuad(quad)));
        statements.sort(Comparator.comparing(this::statementKey));

        Map<String, String> labels = collectLabels(statements);
        Set<String> resources = collectResources(statements);
        Set<String> selected = selectResources(resources, statements, labels, query);
        List<String> sortedResources = selected.stream().sorted().toList();
        int offset = (query.page() - 1) * query.size();
        int end = Math.min(offset + query.size(), sortedResources.size());
        List<String> pageResources = offset >= sortedResources.size()
                ? List.of()
                : sortedResources.subList(offset, end);
        Set<String> pageSet = Set.copyOf(pageResources);
        List<OntologyNode> nodes = pageResources.stream()
                .map(iri -> new OntologyNode(iri, labels.get(iri)))
                .toList();
        List<OntologyEdge> edges = statements.stream()
                .filter(statement -> pageSet.contains(statement.subjectIri()))
                .filter(statement -> statement.object().kind() != OntologyTerm.Kind.IRI
                        || pageSet.contains(statement.object().value()))
                .map(statement -> new OntologyEdge(
                        statement.subjectIri(), statement.predicateIri(), statement.object()))
                .toList();
        return new OntologySubgraph(ref, nodes, edges, sortedResources.size(), end < sortedResources.size());
    }

    private Set<String> collectResources(List<OntologyStatement> statements) {
        Set<String> resources = new LinkedHashSet<>();
        for (OntologyStatement statement : statements) {
            resources.add(statement.subjectIri());
            if (statement.object().kind() == OntologyTerm.Kind.IRI) {
                resources.add(statement.object().value());
            }
        }
        return resources;
    }

    private Set<String> selectResources(
            Set<String> resources,
            List<OntologyStatement> statements,
            Map<String, String> labels,
            GraphQuery query) {
        Set<String> selected = new LinkedHashSet<>(resources);
        if (query.keyword() != null) {
            String keyword = query.keyword().toLowerCase(Locale.ROOT);
            selected.removeIf(iri -> !iri.toLowerCase().contains(keyword)
                    && !labels.getOrDefault(iri, "").toLowerCase(Locale.ROOT).contains(keyword));
        }
        if (query.focusNodeIri() != null) {
            if (!resources.contains(query.focusNodeIri())) {
                return Set.of();
            }
            Set<String> reachable = new LinkedHashSet<>();
            Map<String, Set<String>> adjacency = adjacency(statements);
            ArrayDeque<NodeDistance> queue = new ArrayDeque<>();
            queue.add(new NodeDistance(query.focusNodeIri(), 0));
            while (!queue.isEmpty()) {
                NodeDistance current = queue.removeFirst();
                if (!reachable.add(current.iri())) {
                    continue;
                }
                if (current.distance() >= query.depth()) {
                    continue;
                }
                for (String next : adjacency.getOrDefault(current.iri(), Set.of())) {
                    queue.addLast(new NodeDistance(next, current.distance() + 1));
                }
            }
            selected.retainAll(reachable);
        }
        return selected;
    }

    private Map<String, Set<String>> adjacency(List<OntologyStatement> statements) {
        Map<String, Set<String>> result = new HashMap<>();
        for (OntologyStatement statement : statements) {
            if (statement.object().kind() != OntologyTerm.Kind.IRI) {
                continue;
            }
            result.computeIfAbsent(statement.subjectIri(), ignored -> new HashSet<>())
                    .add(statement.object().value());
            result.computeIfAbsent(statement.object().value(), ignored -> new HashSet<>())
                    .add(statement.subjectIri());
        }
        return result;
    }

    private Map<String, String> collectLabels(List<OntologyStatement> statements) {
        Map<String, String> labels = new HashMap<>();
        statements.stream()
                .filter(statement -> RDFS_LABEL.equals(statement.predicateIri())
                        || SKOS_PREF_LABEL.equals(statement.predicateIri()))
                .filter(statement -> statement.object().kind() == OntologyTerm.Kind.LITERAL)
                .sorted(Comparator.comparing(this::statementKey))
                .forEach(statement -> labels.putIfAbsent(statement.subjectIri(), statement.object().value()));
        return labels;
    }

    private String statementKey(OntologyStatement statement) {
        return statement.subjectIri() + "|" + statement.predicateIri() + "|"
                + statement.object().kind() + "|" + statement.object().value();
    }

    private void addMapping(
            DatasetGraph dataset,
            Node graphNode,
            Node subject,
            OntologyNodeUpdate.PhysicalMapping mapping) {
        if (mapping == null) {
            return;
        }
        addLiteral(dataset, graphNode, subject, MAPPING_SOURCE, mapping.sourceId(), null);
        addLiteral(dataset, graphNode, subject, PHYSICAL_OBJECT, mapping.physicalObject(), null);
        addOptionalLiteral(dataset, graphNode, subject, PHYSICAL_FIELD, mapping.physicalField());
        addLiteral(dataset, graphNode, subject, MAPPING_KIND, mapping.mappingKind(), null);
    }

    private void addOptionalLiteral(
            DatasetGraph dataset,
            Node graphNode,
            Node subject,
            String predicate,
            String value) {
        if (value != null) {
            addLiteral(dataset, graphNode, subject, predicate, value, null);
        }
    }

    private void addLiteral(
            DatasetGraph dataset,
            Node graphNode,
            Node subject,
            String predicate,
            String value,
            String datatype) {
        Node object = datatype == null
                ? NodeFactory.createLiteralString(value)
                : NodeFactory.createLiteralDT(value, org.apache.jena.datatypes.TypeMapper.getInstance()
                        .getSafeTypeByName(datatype));
        dataset.add(new Quad(graphNode, subject, NodeFactory.createURI(predicate), object));
    }

    private long countGraph(DatasetGraph dataset, Node graphNode) {
        long count = 0;
        Iterator<Quad> iterator = dataset.find(graphNode, Node.ANY, Node.ANY, Node.ANY);
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }
        return count;
    }

    private Node absoluteIri(String value, String field) {
        if (value == null || value.isBlank() || !(value.startsWith("urn:")
                || value.startsWith("http://") || value.startsWith("https://"))) {
            throw new IllegalArgumentException(field + " must be an absolute IRI");
        }
        return NodeFactory.createURI(value.trim());
    }

    private String normalizeDatatype(String dataType) {
        String normalized = dataType.trim();
        if (normalized.startsWith("urn:") || normalized.startsWith("http://")
                || normalized.startsWith("https://")) {
            return normalized;
        }
        return XSD_NAMESPACE + switch (normalized.toLowerCase(Locale.ROOT)) {
            case "int", "integer" -> "integer";
            case "long", "bigint" -> "long";
            case "decimal", "number", "numeric" -> "decimal";
            case "double", "float" -> "double";
            case "bool", "boolean" -> "boolean";
            case "date" -> "date";
            case "datetime", "timestamp" -> "dateTime";
            default -> "string";
        };
    }

    private record NodeDistance(String iri, int distance) {
    }
}
