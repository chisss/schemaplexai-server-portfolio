package com.schemaplexai.service.agent.execution.policy;

import com.schemaplexai.common.enums.ExecutionModeEnum;
import org.springframework.stereotype.Component;

/**
 * 执行模式提示词贡献器
 */
@Component
public class ExecutionModePromptContributor {

    public String buildPrompt(ExecutionModePolicy policy) {
        ExecutionModeEnum mode = policy != null && policy.getMode() != null ? policy.getMode() : ExecutionModeEnum.AUTO;
        return switch (mode) {
            case PLAN -> """
                    当前执行模式：计划模式。
                    请先说明任务计划；读取信息可直接进行，涉及创建、修改、删除或外部副作用的操作会由系统请求用户审批。
                    """;
            case SUGGEST -> """
                    当前执行模式：建议模式。
                    请只输出建议方案、候选工具与参数，不要期待真实工具被执行。
                    """;
            default -> """
                    当前执行模式：自动模式。
                    请根据用户目标推进任务；高风险或写入动作可能由系统要求审批。
                    """;
        };
    }
}
