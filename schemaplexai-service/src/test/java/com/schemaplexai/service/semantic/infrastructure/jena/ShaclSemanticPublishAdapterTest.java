package com.schemaplexai.service.semantic.infrastructure.jena;

import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishPreparation;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShaclSemanticPublishAdapterTest {

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String RDFS_SUB_CLASS = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    private static final String SH = "http://www.w3.org/ns/shacl#";
    private static final String XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer";

    @TempDir
    Path tempDir;

    private SemanticDatasetManager datasetManager;
    private SemanticStoreProperties properties;
    private SemanticGraphIriFactory graphIriFactory;
    private JenaOntologyStoreAdapter ontologyStore;
    private JenaSemanticPublishAdapter publishAdapter;
    private OntologyGraphRef ref;

    @BeforeEach
    void setUp() {
        properties = new SemanticStoreProperties();
        properties.setDirectory(tempDir.resolve("tdb2"));
        datasetManager = new SemanticDatasetManager(properties);
        graphIriFactory = new SemanticGraphIriFactory();
        JenaOntologyStatementMapper mapper = new JenaOntologyStatementMapper();
        ontologyStore = new JenaOntologyStoreAdapter(
                datasetManager,
                new TenantDatasetViewFactory(graphIriFactory),
                graphIriFactory,
                properties,
                mapper);
        publishAdapter = new JenaSemanticPublishAdapter(
                datasetManager,
                graphIriFactory,
                properties,
                mapper,
                new RestrictedRdfsMaterializer());
        ref = new OntologyGraphRef("tenant-a", "people", 1);
    }

    @AfterEach
    void tearDown() {
        datasetManager.close();
    }

    @Test
    void reportsShaclViolationWithoutPublishingGraphs() {
        ontologyStore.replaceDraft(new OntologyGraph(ref, List.of(
                iri("urn:example:alice", RDF_TYPE, "urn:example:Student"),
                iri("urn:example:Student", RDFS_SUB_CLASS, "urn:example:Person"))));

        PublishPreparation preparation = publishAdapter.prepare(ref, personShapes(), "validation-1");

        assertThat(preparation.conforms()).isFalse();
        assertThat(preparation.validationReport()).contains("urn:example:alice");
        assertThatThrownBy(() -> publishAdapter.commit(preparation))
                .isInstanceOf(IllegalArgumentException.class);
        publishAdapter.discard(preparation);
        assertThat(graphSize(graphIriFactory.create("tenant-a", "people", 1).inferred())).isZero();
    }

    @Test
    void publishesShapesAndWhitelistedInferenceWithStableChecksum() {
        ontologyStore.replaceDraft(new OntologyGraph(ref, List.of(
                iri("urn:example:alice", RDF_TYPE, "urn:example:Student"),
                iri("urn:example:Student", RDFS_SUB_CLASS, "urn:example:Person"),
                literal("urn:example:alice", RDFS_LABEL, "Alice"))));

        PublishPreparation preparation = publishAdapter.prepare(ref, personShapes(), "validation-2");
        publishAdapter.commit(preparation);

        SemanticGraphIriFactory.GraphSet graphs = graphIriFactory.create("tenant-a", "people", 1);
        assertThat(preparation.conforms()).isTrue();
        assertThat(preparation.checksum()).startsWith("sha256:").hasSize(71);
        assertThat(preparation.tripleCount()).isEqualTo(4);
        assertThat(graphSize(graphs.shapes())).isEqualTo(personShapes().size());
        assertThat(hasTriple(
                graphs.inferred(), "urn:example:alice", RDF_TYPE, "urn:example:Person")).isTrue();
    }

    @Test
    void rollsBackTemporaryGraphsWhenInferenceQuotaIsExceeded() {
        properties.setMaxInferredTriples(0);
        ontologyStore.replaceDraft(new OntologyGraph(ref, List.of(
                iri("urn:example:alice", RDF_TYPE, "urn:example:Student"),
                iri("urn:example:Student", RDFS_SUB_CLASS, "urn:example:Person"),
                literal("urn:example:alice", RDFS_LABEL, "Alice"))));

        assertThatThrownBy(() -> publishAdapter.prepare(ref, personShapes(), "quota-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inferred graph");

        SemanticGraphIriFactory.GraphSet graphs = graphIriFactory.create("tenant-a", "people", 1);
        assertThat(graphSize(graphs.temporary("quota-1-asserted"))).isZero();
        assertThat(graphSize(graphs.temporary("quota-1-shapes"))).isZero();
    }

    @Test
    void rejectsExecutableShaclExtensions() {
        List<OntologyStatement> unsafeShapes = List.of(new OntologyStatement(
                "urn:shape:Unsafe",
                SH + "select",
                OntologyTerm.literal("SELECT * WHERE { ?s ?p ?o }")));

        assertThatThrownBy(() -> publishAdapter.prepare(ref, unsafeShapes, "unsafe-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executable SHACL");
    }

    @Test
    void rejectsCommitWhenAssertedGraphChangedAfterValidation() {
        ontologyStore.replaceDraft(new OntologyGraph(ref, List.of(
                iri("urn:example:alice", RDF_TYPE, "urn:example:Person"),
                literal("urn:example:alice", RDFS_LABEL, "Alice"))));
        PublishPreparation preparation = publishAdapter.prepare(ref, personShapes(), "validation-3");
        ontologyStore.replaceDraft(new OntologyGraph(ref, List.of(
                iri("urn:example:bob", RDF_TYPE, "urn:example:Person"),
                literal("urn:example:bob", RDFS_LABEL, "Bob"))));

        assertThatThrownBy(() -> publishAdapter.commit(preparation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed after validation");
        publishAdapter.discard(preparation);
    }

    private List<OntologyStatement> personShapes() {
        return List.of(
                iri("urn:shape:Person", RDF_TYPE, SH + "NodeShape"),
                iri("urn:shape:Person", SH + "targetClass", "urn:example:Person"),
                iri("urn:shape:Person", SH + "property", "urn:shape:PersonLabel"),
                iri("urn:shape:PersonLabel", SH + "path", RDFS_LABEL),
                new OntologyStatement(
                        "urn:shape:PersonLabel",
                        SH + "minCount",
                        OntologyTerm.typedLiteral("1", XSD_INTEGER)));
    }

    private OntologyStatement iri(String subject, String predicate, String object) {
        return new OntologyStatement(subject, predicate, OntologyTerm.iri(object));
    }

    private OntologyStatement literal(String subject, String predicate, String value) {
        return new OntologyStatement(subject, predicate, OntologyTerm.literal(value));
    }

    private long graphSize(String graphIri) {
        Node graph = NodeFactory.createURI(graphIri);
        return datasetManager.read(dataset -> dataset.getGraph(graph).size());
    }

    private boolean hasTriple(String graphIri, String subject, String predicate, String object) {
        return datasetManager.read(dataset -> dataset.contains(
                NodeFactory.createURI(graphIri),
                NodeFactory.createURI(subject),
                NodeFactory.createURI(predicate),
                NodeFactory.createURI(object)));
    }
}
