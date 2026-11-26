package com.schemaplexai.service.semantic.infrastructure.jena;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;
import com.schemaplexai.service.semantic.domain.port.OntologyArtifactCompilerPort;
import lombok.RequiredArgsConstructor;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.sparql.core.Quad;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_KIND;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_SOURCE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_FIELD;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_OBJECT;

/** 校验发布产物时只使用内存图，数据库版本事务不依赖 TDB2。 */
@Component
@RequiredArgsConstructor
class JenaOntologyArtifactCompiler implements OntologyArtifactCompilerPort {

    private static final Set<String> MAPPING_PREDICATES = Set.of(MAPPING_KIND, MAPPING_SOURCE, PHYSICAL_OBJECT, PHYSICAL_FIELD);
    private final JenaOntologyStatementMapper statementMapper;
    private final JenaOntologyStoreAdapter graphReader;
    private final RestrictedRdfsMaterializer materializer;
    private final SemanticStoreProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public String turtle(List<OntologyStatement> statements) {
        if (statements.size() > properties.getMaxAssertedTriples()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "本体配置超出图容量");
        }
        Graph graph = GraphMemFactory.createDefaultGraph();
        try {
            var name = NodeFactory.createURI("urn:spx:artifact");
            statements.forEach(statement -> graph.add(statementMapper.toQuad(name, statement).asTriple()));
            return turtle(graph);
        } finally {
            graph.close();
        }
    }

    @Override
    public OntologySubgraph query(OntologyGraphRef ref, String ontologyTurtle, GraphQuery query, boolean includeMappings) {
        Graph graph = GraphMemFactory.createDefaultGraph();
        try {
            RDFParser.fromString(ontologyTurtle, Lang.TURTLE).parse(graph);
            var name = NodeFactory.createURI("urn:spx:artifact");
            List<OntologyStatement> statements = new ArrayList<>();
            graph.stream()
                    .filter(triple -> includeMappings || !MAPPING_PREDICATES.contains(triple.getPredicate().getURI()))
                    .map(triple -> statementMapper.fromQuad(new Quad(name, triple)))
                    .forEach(statements::add);
            return graphReader.queryStatements(ref, query, statements);
        } finally {
            graph.close();
        }
    }

    @Override
    public ReleaseCheck validate(OntologyConfigurationArtifact artifact, String sourceSnapshotId,
                                 PublishedQueryProfile queryProfile) {
        if (artifact.configuration().classes().isEmpty()) {
            return new ReleaseCheck(false, "至少配置一个业务概念后再检查或发布", null, 0, null);
        }
        Graph assertions = GraphMemFactory.createDefaultGraph();
        Graph shapes = GraphMemFactory.createDefaultGraph();
        Graph inferred = GraphMemFactory.createDefaultGraph();
        try {
            RDFParser.fromString(artifact.ontologyTurtle(), Lang.TURTLE).parse(assertions);
            RDFParser.fromString(artifact.shapesTurtle(), Lang.TURTLE).parse(shapes);
            var report = ShaclValidator.get().validate(shapes, assertions);
            if (!report.conforms()) {
                return new ReleaseCheck(false, JenaSemanticPublishAdapter.reportText(report), null, 0, null);
            }
            try {
                materializer.materialize(assertions, properties.getMaxInferredTriples()).forEach(inferred::add);
            } catch (IllegalArgumentException exception) {
                return new ReleaseCheck(false, "推理结果超过本体发布容量", null, 0, null);
            }
            String checksum = checksum(artifact, sourceSnapshotId, queryProfile, assertions, shapes, inferred);
            return new ReleaseCheck(true, null, checksum, (long) assertions.size() + inferred.size(), turtle(inferred));
        } finally {
            assertions.close();
            shapes.close();
            inferred.close();
        }
    }

    private String turtle(Graph graph) {
        var output = new ByteArrayOutputStream();
        RDFDataMgr.write(output, graph, Lang.TURTLE);
        return output.toString(StandardCharsets.UTF_8);
    }

    private String checksum(OntologyConfigurationArtifact artifact, String sourceSnapshotId,
                            PublishedQueryProfile queryProfile, Graph assertions, Graph shapes, Graph inferred) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "spx-ontology-artifact-v1");
            update(digest, sourceSnapshotId == null ? "" : sourceSnapshotId);
            update(digest, objectMapper.writeValueAsString(artifact.configuration()));
            if (queryProfile != null) {
                update(digest, "query");
                update(digest, objectMapper.writeValueAsString(queryProfile));
            }
            digestGraph(digest, "asserted", assertions);
            digestGraph(digest, "shapes", shapes);
            digestGraph(digest, "inferred", inferred);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("无法计算本体发布校验和", exception);
        }
    }

    private void digestGraph(MessageDigest digest, String name, Graph graph) {
        update(digest, name);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(graph.size()).array());
        graph.stream().map(this::line).sorted().forEach(triple -> update(digest, triple));
    }

    private String line(Triple triple) {
        return NodeFmtLib.strNT(triple.getSubject()) + " " + NodeFmtLib.strNT(triple.getPredicate())
                + " " + NodeFmtLib.strNT(triple.getObject());
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
