package com.schemaplexai.service.monitor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceFailureClassifierTest {

    private final TraceFailureClassifier classifier = new TraceFailureClassifier();

    @Test
    void shouldClassifyToolFailureAsRecoverableExploration() {
        TraceFailureClassification result = classifier.classify("TOOL", "sys.read", "FAILED", "PATH_NOT_FOUND");

        assertThat(result.category()).isEqualTo(TraceFailureClassifier.RECOVERABLE_TOOL_EXPLORATION);
        assertThat(result.recoverable()).isTrue();
    }

    @Test
    void shouldClassifyModelChannelFailureAsRecoverable() {
        TraceFailureClassification result = classifier.classify("LLM", "qwen", "FAILED", "InvalidParameter Not support");

        assertThat(result.category()).isEqualTo(TraceFailureClassifier.MODEL_CHANNEL);
        assertThat(result.recoverable()).isTrue();
    }

    @Test
    void shouldClassifyAgentFailureAsBlocking() {
        TraceFailureClassification result = classifier.classify("AGENT", "Agent Execution", "FAILED", "node failed");

        assertThat(result.category()).isEqualTo(TraceFailureClassifier.BLOCKING_ERROR);
        assertThat(result.recoverable()).isFalse();
    }
}
