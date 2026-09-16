package com.schemaplexai.service.semantic.infrastructure.jena;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticGraphIriFactoryTest {

    private final SemanticGraphIriFactory factory = new SemanticGraphIriFactory();

    @Test
    void createsStableServerOwnedGraphNames() {
        SemanticGraphIriFactory.GraphSet graphs = factory.create("tenant-1", "orders", 2);

        assertThat(graphs.asserted()).isEqualTo("urn:spx:tenant:tenant-1:semantic:orders:v:2:asserted");
        assertThat(graphs.shapes()).endsWith(":shapes");
        assertThat(graphs.inferred()).endsWith(":inferred");
        assertThat(graphs.temporary("validation-42")).endsWith(":temporary:validation-42");
        assertThat(SemanticGraphIriFactory.GraphSet.class.getConstructors()).isEmpty();
    }

    @Test
    void rejectsClientControlledIriFragments() {
        assertThatThrownBy(() -> factory.create("tenant/other", "orders", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create(" tenant", "orders", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create("tenant", "orders", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create("tenant", "orders", 1).temporary("other/operation"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
