package com.schemaplexai.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelHealthErrorClassifierTest {

    private final AiModelHealthErrorClassifier classifier = new AiModelHealthErrorClassifier();

    @Test
    void shouldClassifyInvalidParameter() {
        assertThat(classifier.classify("InvalidParameter: max_tokens not support"))
                .isEqualTo(AiModelHealthErrorClassifier.INVALID_PARAMETER);
    }

    @Test
    void shouldClassifyAuthFailure() {
        assertThat(classifier.classify("401 unauthorized invalid api key"))
                .isEqualTo(AiModelHealthErrorClassifier.AUTH_FAILED);
    }

    @Test
    void shouldClassifyCooldownAndQuota() {
        assertThat(classifier.classify("所有候选模型当前处于冷却期"))
                .isEqualTo(AiModelHealthErrorClassifier.COOLDOWN);
        assertThat(classifier.classify("insufficient_quota"))
                .isEqualTo(AiModelHealthErrorClassifier.QUOTA_EXCEEDED);
    }
}
