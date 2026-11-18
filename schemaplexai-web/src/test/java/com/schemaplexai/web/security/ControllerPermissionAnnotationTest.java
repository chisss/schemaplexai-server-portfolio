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
import com.schemaplexai.web.controller.ExecutionSteeringController;
import com.schemaplexai.web.controller.SecurityComplianceController;
import com.schemaplexai.web.controller.SystemController;
import com.schemaplexai.web.controller.TenantController;
import com.schemaplexai.web.controller.WorkflowController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controller 权限注解防护测试。
 *
 * <p>两层防护（审计 L3）：
 * <ol>
 *   <li>扫描式：所有 controller 的写端点（POST/PUT/DELETE/PATCH）必须有
 *       类级或方法级 @PreAuthorize——新增端点自动纳入覆盖，无需手工登记白名单；
 *       历史欠账 controller 在 EXEMPT_CONTROLLERS 中显式登记（治理一个移除一个）。</li>
 *   <li>白名单式：高价值高危写端点逐一断言具体权限码，防止权限码退化。</li>
 * </ol>
 */
class ControllerPermissionAnnotationTest {

    private static final String CONTROLLER_PACKAGE = "com.schemaplexai.web.controller";

    /**
     * 历史欠账豁免清单：尚未完成权限码设计的 controller（写端点暂无 @PreAuthorize）。
     * 新增端点落入非豁免 controller 时本测试即红；豁免清单只减不增，逐步治理。
     */
    private static final Set<String> EXEMPT_CONTROLLERS = Set.of(            "AuthController",         // 登录/注册公开端点
            "ConversationController",
            "DirectChatController",
            "EvaluationController",
            "InAppMessageController",
            "MessageTemplateController",
            "MonitorController",
            "NotificationChannelController",
            "QualityConfigController",
            "QualityController",
            "ReviewController",
            "SkillController",
            "ToolApprovalAmendmentController",
            "UserMemoryController",
            "UserMemorySettingController"
    );

    @Test
    void shouldRequirePreAuthorizeOnAllWriteEndpointsOutsideExemptList() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> controllerClass : scanControllers()) {
            if (EXEMPT_CONTROLLERS.contains(controllerClass.getSimpleName())) {
                continue;
            }
            boolean classLevelAuthorized = controllerClass.isAnnotationPresent(PreAuthorize.class);
            String classPathPrefix = resolveClassPathPrefix(controllerClass);
            for (Method method : controllerClass.getDeclaredMethods()) {
                if (!isWriteMethod(method) || method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                boolean methodLevelAuthorized = method.isAnnotationPresent(PreAuthorize.class);
                if (!classLevelAuthorized && !methodLevelAuthorized) {
                    violations.add(controllerClass.getSimpleName() + "#" + method.getName()
                            + " (" + classPathPrefix + " 缺少 @PreAuthorize)");
                }
            }
        }
        assertThat(violations)
                .as("非豁免 controller 的写端点必须声明 @PreAuthorize（新增端点自动纳入扫描）")
                .isEmpty();
    }

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
        assertPermission(ExecutionSteeringController.class, "steer", "AGENT_EXECUTE",
                String.class, com.schemaplexai.model.dto.agent.SteeringRequest.class);
    }

    private List<Class<?>> scanControllers() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            controllers.add(Class.forName(definition.getBeanClassName()));
        }
        assertThat(controllers).as("controller 包扫描结果不得为空").isNotEmpty();
        return controllers;
    }

    private String resolveClassPathPrefix(Class<?> controllerClass) {
        RequestMapping mapping = controllerClass.getAnnotation(RequestMapping.class);
        if (mapping != null && mapping.value().length > 0) {
            return mapping.value()[0];
        }
        return "";
    }

    private boolean isWriteMethod(Method method) {
        return method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)
                || method.isAnnotationPresent(PatchMapping.class);
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
