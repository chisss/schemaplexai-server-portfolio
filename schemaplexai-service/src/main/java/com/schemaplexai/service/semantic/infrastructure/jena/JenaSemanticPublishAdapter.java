package com.schemaplexai.service.semantic.infrastructure.jena;

import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishPreparation;
import com.schemaplexai.service.semantic.domain.port.SemanticPublishPort;
import lombok.RequiredArgsConstructor;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.GraphView;
import org.apache.jena.sparql.core.Quad;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 基于临时图执行 SHACL、受限规则物化和版本图发布。 */
@Component
@RequiredArgsConstructor
public class JenaSemanticPublishAdapter implements SemanticPublishPort {

    private static final int MAX_REPORT_LENGTH = 16_000;
    private static final Set<String> FORBIDDEN_SHAPE_PREDICATES = Set.of(
            "http://www.w3.org/2002/07/owl#imports",
            "http://www.w3.org/ns/shacl#ask",
            "http://www.w3.org/ns/shacl#construct",
            "http://www.w3.org/ns/shacl#expression",
            "http://www.w3.org/ns/shacl#js",
            "http://www.w3.org/ns/shacl#jsFunctionName",
            "http://www.w3.org/ns/shacl#jsLibrary",
            "http://www.w3.org/ns/shacl#jsLibraryURL",
            "http://www.w3.org/ns/shacl#nodeValidator",
            "http://www.w3.org/ns/shacl#propertyValidator",
            "http://www.w3.org/ns/shacl#rule",
            "http://www.w3.org/ns/shacl#select",
            "http://www.w3.org/ns/shacl#sparql",
            "http://www.w3.org/ns/shacl#validator");

    private final SemanticDatasetManager datasetManager;
    private final SemanticGraphIriFactory graphIriFactory;
    private final SemanticStoreProperties properties;
    private final JenaOntologyStatementMapper statementMapper;
    private final RestrictedRdfsMaterializer materializer;

    @Override
    public PublishPreparation prepare(
            OntologyGraphRef ref,
            List<OntologyStatement> shapes,
            String operationId) {
        if (ref == null) {
            throw new IllegalArgumentException("ref is required");
        }
        List<OntologyStatement> safeShapes = shapes == null ? List.of() : List.copyOf(shapes);
        if (safeShapes.isEmpty()) {
            throw new IllegalArgumentException("SHACL shapes are required");
        }
        if (safeShapes.size() > properties.getMaxAssertedTriples()) {
            throw new IllegalArgumentException("SHACL shapes exceed configured triple quota");
        }
        validateSafeShapes(safeShapes);
        return datasetManager.calculateWrite(dataset -> prepareInTransaction(
                dataset, ref, safeShapes, operationId));
    }

    @Override
    public void commit(PublishPreparation preparation) {
        requireConforming(preparation);
        datasetManager.write(dataset -> {
            SemanticGraphIriFactory.GraphSet graphs = graphIriFactory.create(
                    preparation.ref().tenantId(), preparation.ref().modelId(), preparation.ref().version());
            TemporaryGraphs temporary = temporaryGraphs(graphs, preparation.operationId());
            validatePreparation(dataset, graphs, temporary, preparation);
            replaceGraph(dataset, temporary.shapes(), NodeFactory.createURI(graphs.shapes()));
            replaceGraph(dataset, temporary.inferred(), NodeFactory.createURI(graphs.inferred()));
            deleteTemporary(dataset, temporary);
        });
    }

    @Override
    public void discard(PublishPreparation preparation) {
        if (preparation == null) {
            return;
        }
        datasetManager.write(dataset -> deleteTemporary(
                dataset,
                temporaryGraphs(
                        graphIriFactory.create(
                                preparation.ref().tenantId(),
                                preparation.ref().modelId(),
                                preparation.ref().version()),
                        preparation.operationId())));
    }

    private PublishPreparation prepareInTransaction(
            DatasetGraph dataset,
            OntologyGraphRef ref,
            List<OntologyStatement> shapes,
            String operationId) {
        SemanticGraphIriFactory.GraphSet graphs = graphIriFactory.create(
                ref.tenantId(), ref.modelId(), ref.version());
        TemporaryGraphs temporary = temporaryGraphs(graphs, operationId);
        deleteTemporary(dataset, temporary);
        copyGraph(dataset, NodeFactory.createURI(graphs.asserted()), temporary.asserted());
        for (OntologyStatement shape : shapes) {
            dataset.add(statementMapper.toQuad(temporary.shapes(), shape));
        }

        Graph assertedGraph = GraphView.createNamedGraph(dataset, temporary.asserted());
        Graph shapesGraph = GraphView.createNamedGraph(dataset, temporary.shapes());
        ValidationReport report = ShaclValidator.get().validate(shapesGraph, assertedGraph);
        if (!report.conforms()) {
            return new PublishPreparation(ref, operationId, false, reportText(report), null, 0);
        }

        Set<Triple> inferred = materializer.materialize(assertedGraph, properties.getMaxInferredTriples());
        for (Triple triple : inferred) {
            dataset.add(new Quad(temporary.inferred(), triple));
        }
        List<Triple> asserted = triples(assertedGraph);
        long tripleCount = asserted.size() + inferred.size();
        String checksum = checksum(asserted, shapesGraph, inferred);
        return new PublishPreparation(ref, operationId, true, null, checksum, tripleCount);
    }

    private void validateSafeShapes(List<OntologyStatement> shapes) {
        for (OntologyStatement shape : shapes) {
            if (FORBIDDEN_SHAPE_PREDICATES.contains(shape.predicateIri())) {
                throw new IllegalArgumentException("executable SHACL extensions are not allowed");
            }
        }
    }

    private void validatePreparation(
            DatasetGraph dataset,
            SemanticGraphIriFactory.GraphSet graphs,
            TemporaryGraphs temporary,
            PublishPreparation preparation) {
        Set<Triple> preparedAsserted = new LinkedHashSet<>(triples(
                GraphView.createNamedGraph(dataset, temporary.asserted())));
        Set<Triple> currentAsserted = new LinkedHashSet<>(triples(
                GraphView.createNamedGraph(dataset, NodeFactory.createURI(graphs.asserted()))));
        if (!preparedAsserted.equals(currentAsserted)) {
            throw new IllegalStateException("asserted graph changed after validation");
        }
        Graph preparedShapes = GraphView.createNamedGraph(dataset, temporary.shapes());
        Set<Triple> preparedInferred = new LinkedHashSet<>(triples(
                GraphView.createNamedGraph(dataset, temporary.inferred())));
        String actualChecksum = checksum(new ArrayList<>(preparedAsserted), preparedShapes, preparedInferred);
        long actualCount = preparedAsserted.size() + preparedInferred.size();
        if (!actualChecksum.equals(preparation.checksum()) || actualCount != preparation.tripleCount()) {
            throw new IllegalStateException("publish preparation integrity check failed");
        }
    }

    private TemporaryGraphs temporaryGraphs(
            SemanticGraphIriFactory.GraphSet graphs,
            String operationId) {
        return new TemporaryGraphs(
                NodeFactory.createURI(graphs.temporary(operationId + "-asserted")),
                NodeFactory.createURI(graphs.temporary(operationId + "-shapes")),
                NodeFactory.createURI(graphs.temporary(operationId + "-inferred")));
    }

    private void replaceGraph(DatasetGraph dataset, Node source, Node target) {
        dataset.deleteAny(target, Node.ANY, Node.ANY, Node.ANY);
        copyGraph(dataset, source, target);
    }

    private void copyGraph(DatasetGraph dataset, Node source, Node target) {
        List<Quad> quads = new ArrayList<>();
        dataset.find(source, Node.ANY, Node.ANY, Node.ANY).forEachRemaining(quads::add);
        for (Quad quad : quads) {
            dataset.add(new Quad(target, quad.asTriple()));
        }
    }

    private void deleteTemporary(DatasetGraph dataset, TemporaryGraphs temporary) {
        dataset.deleteAny(temporary.asserted(), Node.ANY, Node.ANY, Node.ANY);
        dataset.deleteAny(temporary.shapes(), Node.ANY, Node.ANY, Node.ANY);
        dataset.deleteAny(temporary.inferred(), Node.ANY, Node.ANY, Node.ANY);
    }

    private List<Triple> triples(Graph graph) {
        List<Triple> result = new ArrayList<>();
        Iterator<Triple> iterator = graph.find(Node.ANY, Node.ANY, Node.ANY);
        iterator.forEachRemaining(result::add);
        return result;
    }

    private String checksum(List<Triple> asserted, Graph shapes, Set<Triple> inferred) {
        List<String> lines = new ArrayList<>();
        asserted.forEach(triple -> lines.add("A|" + format(triple)));
        triples(shapes).forEach(triple -> lines.add("S|" + format(triple)));
        inferred.forEach(triple -> lines.add("I|" + format(triple)));
        lines.sort(String::compareTo);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String format(Triple triple) {
        return NodeFmtLib.strNT(triple.getSubject()) + " "
                + NodeFmtLib.strNT(triple.getPredicate()) + " "
                + NodeFmtLib.strNT(triple.getObject());
    }

    private String reportText(ValidationReport report) {
        List<String> messages = report.getEntries().stream()
                .map(entry -> formatReportNode(entry.focusNode()) + ": "
                        + (entry.message() == null ? "constraint violation" : entry.message()))
                .sorted()
                .distinct()
                .toList();
        if (messages.isEmpty()) {
            return "SHACL validation failed";
        }
        StringBuilder result = new StringBuilder();
        for (String message : messages) {
            String separator = result.isEmpty() ? "" : "; ";
            int remaining = MAX_REPORT_LENGTH - result.length() - separator.length();
            if (remaining <= 0) {
                break;
            }
            result.append(separator);
            result.append(message, 0, Math.min(message.length(), Math.max(remaining, 0)));
        }
        return result.toString();
    }

    private String formatReportNode(Node node) {
        return node == null ? "unknown focus node" : NodeFmtLib.strNT(node);
    }

    private void requireConforming(PublishPreparation preparation) {
        if (preparation == null || !preparation.conforms()) {
            throw new IllegalArgumentException("only conforming preparation can be committed");
        }
    }

    private record TemporaryGraphs(Node asserted, Node shapes, Node inferred) {
    }
}
