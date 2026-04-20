package com.schemaplexai.task;

import com.schemaplexai.service.agent.memory.AgentMemoryConsolidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agent 记忆整合定时任务
 * 每小时执行一次，对所有 Agent 的短期记忆进行整合压缩
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMemoryConsolidationTask {

    private final AgentMemoryConsolidationService agentMemoryConsolidationService;

    /**
     * 每小时执行记忆整合
     */
    @Scheduled(fixedRate = 3600000)
    public void consolidateMemory() {
        log.debug("开始 Agent 记忆整合...");
        try {
            agentMemoryConsolidationService.consolidateAll();
            log.info("Agent 记忆整合完成");
        } catch (Exception e) {
            log.error("Agent 记忆整合异常", e);
        }
    }
}
