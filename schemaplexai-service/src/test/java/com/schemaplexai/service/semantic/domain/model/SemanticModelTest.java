package com.schemaplexai.service.semantic.domain.model;

import com.schemaplexai.service.semantic.common.SemanticModelStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticModelTest {

    @Test
    void updatesProfileAndRevisionInsideAggregate() {
        SemanticModel model = SemanticModel.create("model-1", "tenant-1", "订单", "sales", null);

        model.updateProfile("订单语义", "commerce", "订单分析模型", 0);

        assertThat(model.getName()).isEqualTo("订单语义");
        assertThat(model.getDomain()).isEqualTo("commerce");
        assertThat(model.getRevision()).isEqualTo(1);
    }

    @Test
    void refusesArchiveWhileAnActiveVersionExists() {
        SemanticModel model = SemanticModel.restore(
                "model-1",
                "tenant-1",
                "订单",
                "sales",
                null,
                "version-1",
                SemanticModelStatus.ACTIVE,
                2);

        assertThatThrownBy(() -> model.archive(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active version");
    }

    @Test
    void rejectsStaleRevision() {
        SemanticModel model = SemanticModel.create("model-1", "tenant-1", "订单", "sales", null);

        assertThatThrownBy(() -> model.updateProfile("订单", "sales", null, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revision conflict");
    }
}
