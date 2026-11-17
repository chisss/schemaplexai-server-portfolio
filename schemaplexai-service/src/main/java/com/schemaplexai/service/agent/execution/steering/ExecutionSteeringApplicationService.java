package com.schemaplexai.service.agent.execution.steering;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.ExecutionSteering;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 执行引导应用服务：归属校验（租户+执行绑定）后投递引导
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionSteeringApplicationService {

    private final ExecutionSteeringService steeringService;
    private final AgentExecutionMapper agentExecutionMapper;

    /**
     * 投递执行中引导（运行中/暂停中均可）
     */
    public ExecutionSteering steer(String executionId, String tenantId, String content) {
        AgentExecution execution = agentExecutionMapper.selectById(executionId);
        if (execution == null || !StringUtils.hasText(execution.getTenantId()) || !execution.getTenantId().equals(tenantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "执行归属校验失败，拒绝投递引导");
        }
        return steeringService.create(tenantId, executionId, content);
    }
}
