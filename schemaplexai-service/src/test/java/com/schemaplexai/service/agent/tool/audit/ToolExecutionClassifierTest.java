package com.schemaplexai.service.agent.tool.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionClassifierTest {

    private final ToolExecutionClassifier classifier = new ToolExecutionClassifier();

    @Test
    void shouldClassifyRecoverableToolExplorationErrors() {
        ToolExecutionClassification missingWorkdir = classifier.classify(ToolExecutionErrorCode.MISSING_WORKDIR);
        ToolExecutionClassification pathNotFound = classifier.classify(ToolExecutionErrorCode.PATH_NOT_FOUND);

        assertThat(missingWorkdir.recoverable()).isTrue();
        assertThat(missingWorkdir.failureCategory()).isEqualTo(ToolExecutionClassifier.RECOVERABLE_TOOL_EXPLORATION);
        assertThat(pathNotFound.recoverable()).isTrue();
        assertThat(pathNotFound.failureCategory()).isEqualTo(ToolExecutionClassifier.RECOVERABLE_TOOL_EXPLORATION);
    }

    @Test
    void shouldClassifyBlockingErrors() {
        ToolExecutionClassification skillNotInstalled = classifier.classify(ToolExecutionErrorCode.SKILL_NOT_INSTALLED);
        ToolExecutionClassification sandboxViolation = classifier.classify(ToolExecutionErrorCode.SANDBOX_VIOLATION);

        assertThat(skillNotInstalled.recoverable()).isFalse();
        assertThat(skillNotInstalled.failureCategory()).isEqualTo(ToolExecutionClassifier.BLOCKING_ERROR);
        assertThat(sandboxViolation.recoverable()).isFalse();
        assertThat(sandboxViolation.failureCategory()).isEqualTo(ToolExecutionClassifier.BLOCKING_ERROR);
    }

    @Test
    void shouldInferErrorCodeFromChineseMessage() {
        ToolExecutionClassification classification = classifier.classifyMessage("系统工具调用缺少 workdir");

        assertThat(classification.errorCode()).isEqualTo(ToolExecutionErrorCode.MISSING_WORKDIR);
        assertThat(classification.recoverable()).isTrue();
    }
}
