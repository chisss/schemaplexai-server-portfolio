package com.schemaplexai.task;

import com.schemaplexai.service.workflow.runtime.WorkflowExecutionRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 工作流执行恢复定时任务
 *
 * <p>用于定期修复 Agent 已结束但工作流节点未推进的异常状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutionRecoveryTask {

    private final WorkflowExecutionRecoveryService workflowExecutionRecoveryService;

    /**
     * 每分钟执行一次恢复，优先修复长时间悬挂的工作流节点。
     */
    @Scheduled(fixedDelayString = "${schemaplexai.workflow.recovery-interval-ms:60000}")
    public void recoverDetachedAgentCallbacks() {
        try {
            int recoveredCount = workflowExecutionRecoveryService.recoverDetachedAgentCallbacks(50);
            if (recoveredCount > 0) {
                log.warn("工作流执行恢复任务完成，本轮共恢复 {} 个脱节节点", recoveredCount);
            } else {
                log.debug("工作流执行恢复任务完成，无需恢复的节点");
            }
        } catch (Exception exception) {
            log.error("工作流执行恢复任务异常", exception);
        }
    }
}
