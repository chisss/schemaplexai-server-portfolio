package com.schemaplexai.service.semantic.infrastructure.jena;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticStorePropertiesTest {

    @Test
    void providesSafeDevelopmentDefaults() {
        SemanticStoreProperties properties = new SemanticStoreProperties();

        assertThat(properties.getDirectory()).isEqualTo(Path.of("./data/semantic-tdb2"));
        assertThat(properties.isSingleWriter()).isTrue();
        assertThat(properties.getMaxAssertedTriples()).isEqualTo(200_000);
        assertThat(properties.getMaxInferredTriples()).isEqualTo(1_000_000);
        assertThat(properties.getMaxGraphNodes()).isEqualTo(500);
        assertThat(properties.getQueryTimeoutMillis()).isEqualTo(3_000);
    }
}
