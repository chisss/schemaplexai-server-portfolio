package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.enums.ExecutionModeEnum;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.service.agent.tool.ToolApprovalAmendmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 执行模式拦截器 — 在工具执行前根据执行模式决定审批策略
 * <ul>
 *     <li>AUTO: 写工具首次需审批（可渐进信任），读工具直接执行</li>
 *     <li>PLAN: 读工具直接执行，写工具必须审批</li>
 *     <li>SUGGEST: 所有工具仅建议不执行</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionModeInterceptor {

    private final ToolApprovalAmendmentService amendmentService;

    /**
     * 拦截决策结果
     */
    public enum Decision {
        /** 直接执行 */
        EXECUTE,
        /** 暂停等待用户审批 */
        REQUIRE_APPROVAL,
        /** 仅作为建议展示，不实际执行 */
        SUGGEST_ONLY
    }

    /**
     * 评估单个工具调用是否需要审批
     *
     * @param executionMode 当前执行模式
     * @param tenantId      租户ID
     * @param agentId       Agent ID
     * @param toolCode      工具编码
     * @param command        工具命令/参数摘要
     * @param ioType        工具IO类型
     * @param isSystemAgent 是否系统内置Agent
     * @return 拦截决策
     */
    public Decision evaluate(String executionMode, String tenantId, String agentId,
                             String toolCode, String command, ToolIoTypeEnum ioType,
                             boolean isSystemAgent) {
        ExecutionModeEnum mode = ExecutionModeEnum.fromCode(executionMode);

        // SUGGEST 模式：所有工具仅建议
        if (mode == ExecutionModeEnum.SUGGEST) {
            log.debug("建议模式: 工具[{}]仅作为建议展示", toolCode);
            return Decision.SUGGEST_ONLY;
        }

        // 非系统Agent不走执行模式拦截，保持原有逻辑
        if (!isSystemAgent) {
            return Decision.EXECUTE;
        }

        boolean isWriteOp = ioType != null && ioType.isWrite();

        // PLAN 模式：读操作直接执行，写操作必须审批
        if (mode == ExecutionModeEnum.PLAN) {
            if (!isWriteOp) {
                return Decision.EXECUTE;
            }
            // 写操作检查渐进信任
            if (amendmentService.isAutoApproved(tenantId, agentId, toolCode, command)) {
                log.debug("计划模式: 工具[{}]渐进信任自动放行", toolCode);
                return Decision.EXECUTE;
            }
            return Decision.REQUIRE_APPROVAL;
        }

        // AUTO 模式：读操作直接执行，写操作检查渐进信任
        if (!isWriteOp) {
            return Decision.EXECUTE;
        }
        if (amendmentService.isAutoApproved(tenantId, agentId, toolCode, command)) {
            log.debug("自动模式: 工具[{}]渐进信任自动放行", toolCode);
            return Decision.EXECUTE;
        }
        return Decision.REQUIRE_APPROVAL;
    }
}
