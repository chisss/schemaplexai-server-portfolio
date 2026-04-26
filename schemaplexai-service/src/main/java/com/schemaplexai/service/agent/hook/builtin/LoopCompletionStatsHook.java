package com.schemaplexai.service.agent.hook.builtin;

import com.schemaplexai.service.agent.hook.AgentHook;
import com.schemaplexai.service.agent.hook.AgentHookContext;
import com.schemaplexai.service.agent.hook.AgentHookType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 循环完成统计 Hook（ON_LOOP_COMPLETE）
 * 记录执行完成后的 Token 用量和耗时统计
 */
@Slf4j
@Component
public class LoopCompletionStatsHook implements AgentHook {

    @Override
    public String name() { return "loop-completion-stats"; }

    @Override
    public AgentHookType type() { return AgentHookType.ON_LOOP_COMPLETE; }

    @Override
    public int order() { return 50; }

    @Override
    public boolean execute(AgentHookContext context) {
        log.info("[LoopStats] executionId={} agentId={} tokenIn={} tokenOut={} elapsedMs={}",
                context.getExecutionId(), context.getAgentId(),
                context.getTotalTokenInput(), context.getTotalTokenOutput(), context.getElapsedMs());
        return true;
    }
}
