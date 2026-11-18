package com.schemaplexai.service.agent.execution.steering;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.ExecutionSteering;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 执行引导应用服务：归属校验（租户+执行绑定+执行所有者）后投递引导
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionSteeringApplicationService {

    private final ExecutionSteeringService steeringService;
    private final AgentExecutionMapper agentExecutionMapper;

    /**
     * 投递执行中引导（运行中/暂停中均可）。
     *
     * <p>归属校验：租户一致 + 当前用户为执行所有者（或超管），
     * 防止同租户有 AGENT_EXECUTE 权限的用户向他人执行注入引导。
     */
    public ExecutionSteering steer(String executionId, String tenantId, String content) {
        AgentExecution execution = agentExecutionMapper.selectById(executionId);
        if (execution == null || !StringUtils.hasText(execution.getTenantId()) || !execution.getTenantId().equals(tenantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "执行归属校验失败，拒绝投递引导");
        }
        // 执行所有者校验：仅执行创建者或超管可注入引导（系统创建的无主执行仅超管可投递）
        if (!isCurrentUserSuperAdmin()
                && (execution.getCreatedBy() == null || !execution.getCreatedBy().equals(SecurityUtil.getCurrentUserId()))) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅执行所有者可投递引导");
        }
        return steeringService.create(tenantId, executionId, content);
    }

    private boolean isCurrentUserSuperAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + CommonConstant.ROLE_SUPER_ADMIN).equalsIgnoreCase(authority.getAuthority()));
    }
}
