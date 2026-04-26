package com.schemaplexai.service.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.dao.mapper.AgentExecutionLogMapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AgentExecutionLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 执行日志服务
 * 负责写入执行日志条目，预留 WebSocket 实时推送扩展点
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLogService {

    private final AgentExecutionLogMapper agentExecutionLogMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 追加一条执行日志
     */
    public void appendLog(String executionId, String agentId, String tenantId,
                          String logLevel, String logType,
                          Integer roundNum, String toolName, String content,
                          Integer tokenDelta, Long elapsedMs) {
        AgentExecutionLog entry = new AgentExecutionLog();
        entry.setExecutionId(executionId);
        entry.setAgentId(agentId);
        entry.setTenantId(tenantId);
        entry.setLogLevel(logLevel);
        entry.setLogType(logType);
        entry.setRoundNum(roundNum);
        entry.setToolName(toolName);
        entry.setContent(content);
        entry.setTokenDelta(tokenDelta);
        entry.setElapsedMs(elapsedMs != null ? elapsedMs.intValue() : null);
        entry.setCreatedAt(LocalDateTime.now());
        agentExecutionLogMapper.insert(entry);

        // TODO: 通过 WebSocket 实时推送日志条目给前端
    }

    /**
     * 查询执行的所有日志（按时间升序）
     */
    public List<AgentExecutionLog> getLogs(String executionId) {
        return agentExecutionLogMapper.selectList(
                new LambdaQueryWrapper<AgentExecutionLog>()
                        .eq(AgentExecutionLog::getExecutionId, executionId)
                        .orderByAsc(AgentExecutionLog::getCreatedAt));
    }

    /**
     * 更新执行状态，防止将已 stopped 的执行覆盖为其他终态
     *
     * @param outputResult 最终输出文本（仅 completed 时有值）
     */
    public void updateExecutionStatus(String executionId, String status,
                                      String errorMessage, Long tokenInput, Long tokenOutput,
                                      String outputResult) {
        AgentExecution update = new AgentExecution();
        update.setId(executionId);
        update.setStatus(status);
        update.setErrorMessage(errorMessage);
        update.setTokenInput(tokenInput);
        update.setTokenOutput(tokenOutput);

        // outputResult 仅在有值时写入，避免覆盖已有结果
        if (StringUtils.hasText(outputResult)) {
            update.setOutputResult(outputResult);
        }

        // 转为 running 时记录开始时间
        if (AgentExecutionStatusEnum.RUNNING.getCode().equals(status)) {
            update.setStartedAt(LocalDateTime.now());
        }

        // 终态记录完成时间
        if (!AgentExecutionStatusEnum.QUEUED.getCode().equals(status)
                && !AgentExecutionStatusEnum.RUNNING.getCode().equals(status)) {
            update.setCompletedAt(LocalDateTime.now());
        }

        // 防止将已 stopped 状态覆盖（用户手动停止后异步线程延迟写入）
        int affected = agentExecutionMapper.update(update,
                new LambdaUpdateWrapper<AgentExecution>()
                        .eq(AgentExecution::getId, executionId)
                        .ne(AgentExecution::getStatus, AgentExecutionStatusEnum.STOPPED.getCode()));
        if (affected == 0) {
            log.warn("执行状态未更新（可能已停止）: executionId={}, targetStatus={}", executionId, status);
        } else {
            log.info("更新执行状态: executionId={}, status={}", executionId, status);
            if (AgentExecutionStatusEnum.COMPLETED.getCode().equals(status)) {
                applicationEventPublisher.publishEvent(new AgentExecutionCompletedEvent(executionId));
            }
        }
    }
}
