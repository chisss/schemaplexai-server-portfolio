package com.schemaplexai.web.security;

import com.schemaplexai.model.dto.agent.AgentExecuteDTO;
import com.schemaplexai.model.dto.approval.ApprovalCenterBatchReviewRequest;
import com.schemaplexai.model.dto.cost.BudgetCreateRequest;
import com.schemaplexai.model.dto.security.SecurityPolicySaveRequest;
import com.schemaplexai.model.dto.system.AiModelCreateRequest;
import com.schemaplexai.model.dto.system.TenantCreateRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateCreateRequest;
import com.schemaplexai.web.controller.AgentController;
import com.schemaplexai.web.controller.ApprovalCenterController;
import com.schemaplexai.web.controller.CostController;
import com.schemaplexai.web.controller.SecurityComplianceController;
import com.schemaplexai.web.controller.SystemController;
import com.schemaplexai.web.controller.TenantController;
import com.schemaplexai.web.controller.WorkflowController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerPermissionAnnotationTest {

    @Test
    void shouldProtectHighRiskWriteEndpointsWithPreAuthorize() throws NoSuchMethodException {
        assertPermission(CostController.class, "createBudget", "COST_BUDGET_MANAGE", BudgetCreateRequest.class);
        assertPermission(SecurityComplianceController.class, "createPolicy", "SECURITY_POLICY_MANAGE",
                SecurityPolicySaveRequest.class, HttpServletRequest.class);
        assertPermission(AgentController.class, "execute", "AGENT_EXECUTE", String.class, AgentExecuteDTO.class);
        assertPermission(TenantController.class, "create", "SYSTEM_TENANT_MANAGE", TenantCreateRequest.class);
        assertPermission(SystemController.class, "createAiModel", "SYSTEM_CONFIG_MANAGE", AiModelCreateRequest.class);
        assertPermission(WorkflowController.class, "createTemplate", "WORKFLOW_MANAGE", WorkflowTemplateCreateRequest.class);
        assertPermission(ApprovalCenterController.class, "batchReview", "APPROVAL_DECIDE",
                ApprovalCenterBatchReviewRequest.class);
    }

    private void assertPermission(Class<?> controllerClass,
                                  String methodName,
                                  String expectedPermission,
                                  Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = controllerClass.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as("%s#%s should declare @PreAuthorize", controllerClass.getSimpleName(), methodName)
                .isNotNull();
        assertThat(annotation.value()).contains(expectedPermission);
    }
}
