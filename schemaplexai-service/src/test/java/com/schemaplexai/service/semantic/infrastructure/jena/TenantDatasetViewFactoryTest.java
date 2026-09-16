package com.schemaplexai.service.semantic.infrastructure.jena;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.Dataset;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.DatasetGraphWrapper;
import org.apache.jena.sparql.core.GraphView;
import org.apache.jena.sparql.core.Quad;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantDatasetViewFactoryTest {

    @Test
    void exposesOnlyGraphsOwnedByTheTenantVersion() {
        SemanticGraphIriFactory.GraphSet graphs = new SemanticGraphIriFactory().create("tenant-a", "orders", 1);
        var source = DatasetGraphFactory.createTxnMem();
        var subject = NodeFactory.createURI("urn:spx:subject");
        var predicate = NodeFactory.createURI("urn:spx:predicate");
        var object = NodeFactory.createLiteralString("value");
        var assertedGraph = NodeFactory.createURI(graphs.asserted());
        var temporaryGraph = NodeFactory.createURI(graphs.temporary("validation-1"));
        var otherTenantGraph = NodeFactory.createURI(
                "urn:spx:tenant:tenant-b:semantic:orders:v:1:asserted");
        source.add(new Quad(assertedGraph, subject, predicate, object));
        source.add(new Quad(temporaryGraph, subject, predicate, object));
        source.add(new Quad(otherTenantGraph, subject, predicate, object));

        Dataset view = new TenantDatasetViewFactory(new SemanticGraphIriFactory())
                .createReadOnlyView(source, "tenant-a", "orders", 1);

        assertThat(view.asDatasetGraph().stream().toList()).hasSize(1);
        assertThat(view.asDatasetGraph().contains(assertedGraph, subject, predicate, object)).isTrue();
        assertThat(view.asDatasetGraph().contains(temporaryGraph, subject, predicate, object)).isFalse();
        assertThat(view.asDatasetGraph().contains(otherTenantGraph, subject, predicate, object)).isFalse();
        assertThat(view.asDatasetGraph()).isNotInstanceOf(DatasetGraphWrapper.class);
        assertThat(DatasetGraphWrapper.unwrap(view.asDatasetGraph())).isSameAs(view.asDatasetGraph());
        assertThat(((GraphView) view.asDatasetGraph().getGraph(assertedGraph)).getDataset())
                .isSameAs(view.asDatasetGraph());
        assertThatThrownBy(() -> view.begin(ReadWrite.WRITE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.asDatasetGraph().add(
                new Quad(assertedGraph, subject, predicate, object)))
                .isInstanceOf(UnsupportedOperationException.class);

        view.close();
        assertThatCode(() -> source.add(new Quad(assertedGraph, subject, predicate, object)))
                .doesNotThrowAnyException();
    }
}
