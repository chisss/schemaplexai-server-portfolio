package com.schemaplexai.service.semantic.infrastructure.jena;

import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyEdge;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyNode;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import org.apache.jena.datatypes.TypeMapper;
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

/** Jena/TDB2 本体图适配器，提供结构化写入和受限邻域读取。 */
@Component
public class JenaOntologyStoreAdapter implements OntologyStorePort {

    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";

    private final SemanticDatasetManager datasetManager;
    private final TenantDatasetViewFactory viewFactory;
    private final SemanticGraphIriFactory graphIriFactory;
    private final SemanticStoreProperties properties;

    public JenaOntologyStoreAdapter(
            SemanticDatasetManager datasetManager,
            TenantDatasetViewFactory viewFactory,
            SemanticGraphIriFactory graphIriFactory,
            SemanticStoreProperties properties) {
        this.datasetManager = datasetManager;
        this.viewFactory = viewFactory;
        this.graphIriFactory = graphIriFactory;
        this.properties = properties;
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
                dataset.add(new Quad(
                        graphNode,
                        NodeFactory.createURI(statement.subjectIri()),
                        NodeFactory.createURI(statement.predicateIri()),
                        toNode(statement.object())));
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
        quads.forEachRemaining(quad -> statements.add(fromQuad(quad)));
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

    private Node toNode(OntologyTerm term) {
        if (term.kind() == OntologyTerm.Kind.IRI) {
            return NodeFactory.createURI(term.value());
        }
        if (term.language() != null) {
            return NodeFactory.createLiteralLang(term.value(), term.language());
        }
        if (term.datatype() != null) {
            return NodeFactory.createLiteralDT(term.value(), TypeMapper.getInstance().getSafeTypeByName(term.datatype()));
        }
        return NodeFactory.createLiteralString(term.value());
    }

    private OntologyStatement fromQuad(Quad quad) {
        return new OntologyStatement(
                quad.getSubject().getURI(),
                quad.getPredicate().getURI(),
                fromNode(quad.getObject()));
    }

    private OntologyTerm fromNode(Node node) {
        if (node.isURI()) {
            return OntologyTerm.iri(node.getURI());
        }
        if (!node.isLiteral()) {
            throw new IllegalStateException("ontology graph contains unsupported RDF object");
        }
        String language = node.getLiteralLanguage();
        if (language != null && !language.isBlank()) {
            return OntologyTerm.languageLiteral(node.getLiteralLexicalForm(), language);
        }
        String datatype = node.getLiteralDatatypeURI();
        return datatype == null
                ? OntologyTerm.literal(node.getLiteralLexicalForm())
                : OntologyTerm.typedLiteral(node.getLiteralLexicalForm(), datatype);
    }

    private String statementKey(OntologyStatement statement) {
        return statement.subjectIri() + "|" + statement.predicateIri() + "|"
                + statement.object().kind() + "|" + statement.object().value();
    }

    private record NodeDistance(String iri, int distance) {
    }
}
