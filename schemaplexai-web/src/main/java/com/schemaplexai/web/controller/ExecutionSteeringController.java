package com.schemaplexai.web.controller;

import com.schemaplexai.common.enums.PermissionCodeEnum;
import com.schemaplexai.common.result.R;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.dto.agent.SteeringRequest;
import com.schemaplexai.service.agent.execution.steering.ExecutionSteeringApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 执行中用户引导（Steering）接口：Agent 执行过程中补充最新引导，原任务不丢失
 */
@RestController
@RequestMapping("/agent/executions/{executionId}/steering")
@RequiredArgsConstructor
public class ExecutionSteeringController {

    private final ExecutionSteeringApplicationService steeringApplicationService;

    @PostMapping
    @Operation(summary = "投递执行中用户引导（对齐 Codex interrupt 语义：追加消息、任务保留、最新优先）")
    @PreAuthorize("@authorizationService.hasPermission(T(com.schemaplexai.common.enums.PermissionCodeEnum).AGENT_EXECUTE.getCode())")
    public R<Map<String, Object>> steer(@PathVariable String executionId,
                                        @Valid @RequestBody SteeringRequest request) {
        String tenantId = SecurityUtil.getCurrentTenantId();
        var steering = steeringApplicationService.steer(executionId, tenantId, request.getContent());
        return R.ok(Map.of(
                "steeringId", steering.getId(),
                "executionId", executionId,
                "seq", steering.getSeq() != null ? steering.getSeq() : 1
        ));
    }
}
