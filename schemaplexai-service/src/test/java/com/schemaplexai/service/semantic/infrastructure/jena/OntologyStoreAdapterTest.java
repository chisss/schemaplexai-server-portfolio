package com.schemaplexai.service.semantic.infrastructure.jena;

import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OntologyStoreAdapterTest {

    @TempDir
    Path tempDir;

    private SemanticDatasetManager datasetManager;
    private JenaOntologyStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        SemanticStoreProperties properties = new SemanticStoreProperties();
        properties.setDirectory(tempDir.resolve("tdb2"));
        properties.setMaxGraphNodes(2);
        datasetManager = new SemanticDatasetManager(properties);
        SemanticGraphIriFactory graphIriFactory = new SemanticGraphIriFactory();
        adapter = new JenaOntologyStoreAdapter(
                datasetManager,
                new TenantDatasetViewFactory(graphIriFactory),
                graphIriFactory,
                properties);
    }

    @AfterEach
    void tearDown() {
        datasetManager.close();
    }

    @Test
    void isolatesTenantsAndKeepsEdgesInsideRequestedPage() {
        adapter.replaceDraft(new OntologyGraph(
                new OntologyGraphRef("tenant-a", "orders", 1),
                List.of(
                        statement("urn:example:a", "urn:example:related", "urn:example:b"),
                        statement("urn:example:b", "urn:example:related", "urn:example:c"),
                        new OntologyStatement("urn:example:a", "http://www.w3.org/2000/01/rdf-schema#label",
                                OntologyTerm.languageLiteral("订单", "zh")),
                        new OntologyStatement("urn:example:a", "urn:example:empty",
                                OntologyTerm.literal("")))));
        adapter.replaceDraft(new OntologyGraph(
                new OntologyGraphRef("tenant-b", "orders", 1),
                List.of(statement("urn:example:other", "urn:example:related", "urn:example:secret"))));

        OntologySubgraph page = adapter.querySubgraph(
                new OntologyGraphRef("tenant-a", "orders", 1), new GraphQuery(null, null, 1, 1, 2));

        assertThat(page.totalNodes()).isEqualTo(3);
        assertThat(page.nodes()).extracting(node -> node.iri())
                .containsExactly("urn:example:a", "urn:example:b");
        assertThat(page.edges())
                .filteredOn(edge -> edge.object().kind() == OntologyTerm.Kind.IRI)
                .singleElement()
                .extracting(edge -> edge.object().value())
                .isEqualTo("urn:example:b");
        assertThat(page.nodes().getFirst().label()).isEqualTo("订单");
        assertThat(page.edges()).anySatisfy(edge -> {
            assertThat(edge.predicateIri()).isEqualTo("urn:example:empty");
            assertThat(edge.object().value()).isEmpty();
        });

        OntologySubgraph other = adapter.querySubgraph(
                new OntologyGraphRef("tenant-b", "orders", 1), new GraphQuery(null, null, 1, 1, 2));
        assertThat(other.nodes()).extracting(node -> node.iri()).containsExactly(
                "urn:example:other", "urn:example:secret");
    }

    @Test
    void appliesFocusDepthAndKeywordFilters() {
        adapter.replaceDraft(new OntologyGraph(
                new OntologyGraphRef("tenant-a", "orders", 1),
                List.of(
                        statement("urn:example:a", "urn:example:related", "urn:example:b"),
                        statement("urn:example:b", "urn:example:related", "urn:example:c"),
                        new OntologyStatement("urn:example:c", "http://www.w3.org/2000/01/rdf-schema#label",
                                OntologyTerm.literal("目标")))));

        OntologySubgraph focused = adapter.querySubgraph(
                new OntologyGraphRef("tenant-a", "orders", 1),
                new GraphQuery("urn:example:a", null, 1, 1, 2));
        assertThat(focused.nodes()).extracting(node -> node.iri())
                .containsExactly("urn:example:a", "urn:example:b");

        OntologySubgraph keyword = adapter.querySubgraph(
                new OntologyGraphRef("tenant-a", "orders", 1),
                new GraphQuery(null, "目标", 1, 1, 2));
        assertThat(keyword.nodes()).extracting(node -> node.iri()).containsExactly("urn:example:c");
    }

    private OntologyStatement statement(String subject, String predicate, String object) {
        return new OntologyStatement(subject, predicate, OntologyTerm.iri(object));
    }
}
