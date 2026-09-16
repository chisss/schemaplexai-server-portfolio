package com.schemaplexai.service.semantic.domain.model;

import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticVersionTest {

    @Test
    void followsValidationPublishAndArchiveLifecycle() {
        SemanticVersion version = draft();

        version.startValidation(0);
        version.publish("sha256:abc", 42, LocalDateTime.of(2026, 9, 16, 12, 0), 1);
        version.archive(2);

        assertThat(version.getStatus()).isEqualTo(SemanticVersionStatus.ARCHIVED);
        assertThat(version.getChecksum()).isEqualTo("sha256:abc");
        assertThat(version.getTripleCount()).isEqualTo(42);
        assertThat(version.getRevision()).isEqualTo(3);
    }

    @Test
    void invalidVersionCanBeValidatedAgain() {
        SemanticVersion version = draft();

        version.startValidation(0);
        version.markInvalid("缺少订单金额映射", 1);
        version.startValidation(2);

        assertThat(version.getStatus()).isEqualTo(SemanticVersionStatus.VALIDATING);
        assertThat(version.getValidationReport()).isNull();
    }

    @Test
    void publishedVersionCannotReturnToValidation() {
        SemanticVersion version = draft();
        version.startValidation(0);
        version.publish("sha256:abc", 1, LocalDateTime.now(), 1);

        assertThatThrownBy(() -> version.startValidation(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHED");
    }

    private SemanticVersion draft() {
        return SemanticVersion.createDraft(
                "version-1",
                "tenant-1",
                "model-1",
                1,
                "urn:spx:tenant:tenant-1:semantic:model-1:v:1:asserted");
    }
}
