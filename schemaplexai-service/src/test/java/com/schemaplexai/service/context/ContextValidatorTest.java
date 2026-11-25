package com.schemaplexai.service.context;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.service.context.validator.ContextValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextValidatorTest {

    private final ContextValidator validator = new ContextValidator(null, null);

    @ParameterizedTest
    @ValueSource(strings = {"global", "agent"})
    void acceptsMissingProjectForIndependentContext(String level) {
        assertDoesNotThrow(() -> validator.validateProjectRequired(level, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "task"})
    void requiresProjectForProjectScopedContext(String level) {
        assertThrows(BusinessException.class, () -> validator.validateProjectRequired(level, " "));
        assertDoesNotThrow(() -> validator.validateProjectRequired(level, "project-1"));
    }
}
