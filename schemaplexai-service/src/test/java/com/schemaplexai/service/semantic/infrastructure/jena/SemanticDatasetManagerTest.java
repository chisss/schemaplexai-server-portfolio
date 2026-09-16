package com.schemaplexai.service.semantic.infrastructure.jena;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Quad;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticDatasetManagerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void rollsBackFailedWriteAndReopensPersistedDataset() {
        SemanticStoreProperties properties = new SemanticStoreProperties();
        properties.setDirectory(tempDirectory.resolve("semantic-tdb2"));
        Quad committed = quad("committed");
        Quad rolledBack = quad("rolled-back");

        try (SemanticDatasetManager manager = new SemanticDatasetManager(properties)) {
            manager.write(dataset -> dataset.add(committed));
            assertThatThrownBy(() -> manager.write(dataset -> {
                dataset.add(rolledBack);
                throw new IllegalStateException("force rollback");
            })).isInstanceOf(IllegalStateException.class);

            boolean committedPresent = manager.read(dataset -> dataset.contains(committed));
            boolean rolledBackPresent = manager.read(dataset -> dataset.contains(rolledBack));
            assertThat(committedPresent).isTrue();
            assertThat(rolledBackPresent).isFalse();
        }

        try (SemanticDatasetManager reopened = new SemanticDatasetManager(properties)) {
            boolean committedPresent = reopened.read(dataset -> dataset.contains(committed));
            boolean rolledBackPresent = reopened.read(dataset -> dataset.contains(rolledBack));
            assertThat(committedPresent).isTrue();
            assertThat(rolledBackPresent).isFalse();
        }
    }

    private Quad quad(String value) {
        return new Quad(
                NodeFactory.createURI("urn:spx:test:graph"),
                NodeFactory.createURI("urn:spx:test:subject"),
                NodeFactory.createURI("urn:spx:test:predicate"),
                NodeFactory.createLiteralString(value));
    }
}
