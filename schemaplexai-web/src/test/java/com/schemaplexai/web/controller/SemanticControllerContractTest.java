package com.schemaplexai.web.controller;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.dto.semantic.SchemaScanRequest;
import com.schemaplexai.model.dto.semantic.SemanticModelCreateRequest;
import com.schemaplexai.model.dto.semantic.SemanticNodeUpdateRequest;
import com.schemaplexai.model.dto.semantic.SemanticVersionActionRequest;
import com.schemaplexai.model.dto.semantic.SemanticVersionCreateRequest;
import com.schemaplexai.service.semantic.application.orchestration.OntologyGraphApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.SemanticModelApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.SemanticPublishApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.SemanticVersionApplicationService;
import com.schemaplexai.web.mapper.SemanticWebMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticControllerContractTest {

    @Mock
    private SemanticModelApplicationService modelService;
    @Mock
    private SemanticVersionApplicationService versionService;
    @Mock
    private OntologyGraphApplicationService graphService;
    @Mock
    private SemanticPublishApplicationService publishService;
    @Mock
    private SemanticWebMapper mapper;

    private Validator validator;
    private SemanticModelController modelController;
    private SemanticOntologyController ontologyController;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        modelController = new SemanticModelController(modelService, versionService, mapper);
        ontologyController = new SemanticOntologyController(graphService, publishService, mapper);
    }

    @Test
    void rejectsInvalidSemanticRequestsBeforeApplicationExecution() {
        var invalidModel = new SemanticModelCreateRequest(" ", "", null);
        var invalidVersion = new SemanticVersionCreateRequest("x".repeat(65));
        var invalidNode = new SemanticNodeUpdateRequest(
                "", null, null, List.of(), null, null, null, -1);
        var invalidAction = new SemanticVersionActionRequest(-1);
        var invalidScan = new SchemaScanRequest("x".repeat(201), null, Set.of());

        assertThat(validator.validate(invalidModel)).hasSize(2);
        assertThat(validator.validate(invalidVersion)).hasSize(1);
        assertThat(validator.validate(invalidNode)).hasSize(2);
        assertThat(validator.validate(invalidAction)).hasSize(1);
        assertThat(validator.validate(invalidScan)).hasSize(1);
    }

    @Test
    void protectsSemanticReadAndWriteEndpointsWithExplicitPermissions() throws Exception {
        assertPermission(SemanticModelController.class, "page", "semantic:model:view");
        assertPermission(SemanticModelController.class, "create", "semantic:model:manage");
        assertPermission(SemanticModelController.class, "createVersion", "semantic:model:manage");
        assertPermission(SemanticOntologyController.class, "graph", "semantic:model:view");
        assertPermission(SemanticOntologyController.class, "updateNode", "semantic:model:manage");
        assertPermission(SemanticOntologyController.class, "publish", "semantic:model:manage");
        assertPermission(DatabaseSchemaController.class, "scan", "database:source:manage");
        assertPermission(DatabaseSchemaController.class, "snapshots", "database:source:view");
    }

    @Test
    void forwardsSourceSnapshotWhenCreatingVersion() {
        String snapshotId = "79d58fb6-8df4-4aec-8939-abac643096fb";
        modelController.createVersion("model-1", new SemanticVersionCreateRequest(snapshotId));

        verify(versionService).createDraft("model-1", snapshotId);
    }

    @Test
    void hidesVersionThatIsNotOwnedByCurrentTenant() {
        when(versionService.get("foreign-version"))
                .thenThrow(new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));

        assertThatThrownBy(() -> modelController.getVersion("foreign-version"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ResultCode.SEMANTIC_VERSION_NOT_FOUND.getCode()));
    }

    @Test
    void preservesRevisionConflictCodeForNodeUpdateAndPublish() {
        SemanticNodeUpdateRequest update = new SemanticNodeUpdateRequest(
                "amount", "订单金额", null, List.of(), "decimal", true, null, 3);
        when(graphService.updateNode(eq("version-1"), eq("urn:order:amount"), any(), eq(3L)))
                .thenThrow(new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT));
        when(publishService.publish("version-1", 3))
                .thenThrow(new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT));

        assertConflict(() -> ontologyController.updateNode("version-1", "urn:order:amount", update));
        assertConflict(() -> ontologyController.publish("version-1", new SemanticVersionActionRequest(3)));
    }

    private void assertPermission(Class<?> controllerType, String methodName, String permission) throws Exception {
        Method method = java.util.Arrays.stream(controllerType.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).contains(permission);
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ResultCode.SEMANTIC_REVISION_CONFLICT.getCode()));
    }
}
