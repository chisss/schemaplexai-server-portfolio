package com.schemaplexai.service.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.dao.mapper.AgentExecutionLogMapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AgentExecutionLog;
import com.schemaplexai.service.desktop.DesktopExecutionTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
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

    public static final String DESKTOP_VERIFICATION_REQUIRED = "桌面工作区缺少本次执行的有效验证回执，请完成配置的验证命令。";
    private static final String DESKTOP_VERIFICATION_PASSED = "DESKTOP_VERIFICATION_PASSED";
    private static final String DESKTOP_VERIFICATION_INVALIDATED = "DESKTOP_VERIFICATION_INVALIDATED";

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

    public List<AgentExecutionLog> recentActivity(String executionId) {
        return agentExecutionLogMapper.selectList(new LambdaQueryWrapper<AgentExecutionLog>()
                .select(AgentExecutionLog::getContent)
                .eq(AgentExecutionLog::getExecutionId, executionId)
                .eq(AgentExecutionLog::getLogType, AgentExecutionActivity.EVENT_TYPE)
                .orderByDesc(AgentExecutionLog::getCreatedAt, AgentExecutionLog::getId)
                .last("LIMIT 600"));
    }

    public void recordDesktopVerification(AgentExecutionContext context, String toolName, Instant checkedSince) {
        appendLog(desktopVerificationRootId(context), context.getAgentId(), context.getTenantId(), "INFO",
                checkedSince == null ? DESKTOP_VERIFICATION_INVALIDATED : DESKTOP_VERIFICATION_PASSED,
                null, toolName, (checkedSince == null ? Instant.now() : checkedSince).toString(), null, null);
    }

    public boolean hasCurrentDesktopVerification(AgentExecutionContext context) {
        List<AgentExecutionLog> evidence = agentExecutionLogMapper.selectList(
                new LambdaQueryWrapper<AgentExecutionLog>()
                        .eq(AgentExecutionLog::getTenantId, context.getTenantId())
                        .eq(AgentExecutionLog::getExecutionId, desktopVerificationRootId(context))
                        .in(AgentExecutionLog::getLogType, DESKTOP_VERIFICATION_PASSED, DESKTOP_VERIFICATION_INVALIDATED));
        Instant latestInvalidation = evidence.stream()
                .filter(entry -> DESKTOP_VERIFICATION_INVALIDATED.equals(entry.getLogType()))
                .map(entry -> Instant.parse(entry.getContent()))
                .max(Instant::compareTo)
                .orElse(Instant.MIN);
        return evidence.stream()
                .filter(entry -> DESKTOP_VERIFICATION_PASSED.equals(entry.getLogType()))
                .map(entry -> Instant.parse(entry.getContent()))
                .anyMatch(start -> start.isAfter(latestInvalidation));
    }

    private String desktopVerificationRootId(AgentExecutionContext context) {
        return StringUtils.hasText(context.getParentExecutionId())
                ? context.getParentExecutionId() : context.getExecutionId();
    }

    /**
     * 更新执行状态，防止将已 stopped 的执行覆盖为其他终态
     *
     * @param outputResult 最终输出文本（仅 completed 时有值）
     */
    public void updateExecutionStatus(String executionId, String status,
                                      String errorMessage, Long tokenInput, Long tokenOutput,
                                      String outputResult) {
        updateExecutionStatus(executionId, null, status, errorMessage, tokenInput, tokenOutput, outputResult, null);
    }

    /** 仅在当前状态符合预期时转换，调用方根据返回值决定是否发布通知。 */
    public boolean tryTransitionExecutionStatus(String executionId, String expectedStatus, String targetStatus,
                                                String errorMessage, Long tokenInput, Long tokenOutput,
                                                String outputResult) {
        return updateExecutionStatus(executionId, expectedStatus, targetStatus,
                errorMessage, tokenInput, tokenOutput, outputResult, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean createTeamChildWhileParentRunning(AgentExecution child) {
        // 与同一父执行的取消写入排序，锁只覆盖子记录插入。
        AgentExecution parent = agentExecutionMapper.selectOne(new LambdaQueryWrapper<AgentExecution>()
                .select(AgentExecution::getId)
                .eq(AgentExecution::getId, child.getParentExecutionId())
                .eq(AgentExecution::getTenantId, child.getTenantId())
                .eq(AgentExecution::getAgentId, child.getAgentId())
                .eq(DesktopExecutionTarget.from(child.getInputContext()) != null,
                        AgentExecution::getCreatedBy, child.getCreatedBy())
                .eq(AgentExecution::getStatus, AgentExecutionStatusEnum.RUNNING.getCode())
                .last("FOR SHARE"));
        if (parent == null) {
            return false;
        }
        if (agentExecutionMapper.insert(child) != 1) {
            throw new IllegalStateException("Team 子执行写入失败");
        }
        return true;
    }

    /** 暂停状态、有效执行时间和累计 Token 一起提交，供下一次恢复读取。 */
    public boolean tryPauseExecution(String executionId, String errorMessage, long tokenInput, long tokenOutput,
                                     String outputResult, long runtimeElapsedMillis) {
        return updateExecutionStatus(executionId, AgentExecutionStatusEnum.RUNNING.getCode(),
                AgentExecutionStatusEnum.PAUSED.getCode(), errorMessage, tokenInput, tokenOutput,
                outputResult, runtimeElapsedMillis);
    }

    private boolean updateExecutionStatus(String executionId, String expectedStatus, String status,
                                          String errorMessage, Long tokenInput, Long tokenOutput,
                                          String outputResult, Long runtimeElapsedMillis) {
        AgentExecution update = new AgentExecution();
        update.setId(executionId);
        update.setStatus(status);
        update.setErrorMessage(errorMessage);
        update.setTokenInput(tokenInput);
        update.setTokenOutput(tokenOutput);
        update.setRuntimeElapsedMillis(runtimeElapsedMillis);

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

        LambdaUpdateWrapper<AgentExecution> condition = new LambdaUpdateWrapper<AgentExecution>()
                .eq(AgentExecution::getId, executionId);
        if (expectedStatus == null) {
            condition.ne(AgentExecution::getStatus, AgentExecutionStatusEnum.STOPPED.getCode());
        } else {
            condition.eq(AgentExecution::getStatus, expectedStatus);
        }
        int affected = agentExecutionMapper.update(update, condition);
        if (affected == 0) {
            log.info("执行状态未更新: executionId={}, expectedStatus={}, targetStatus={}",
                    executionId, expectedStatus, status);
            return false;
        }
        log.info("更新执行状态: executionId={}, status={}", executionId, status);
        if (AgentExecutionStatusEnum.COMPLETED.getCode().equals(status)) {
            try {
                applicationEventPublisher.publishEvent(new AgentExecutionCompletedEvent(executionId));
            } catch (RuntimeException e) {
                log.error("执行完成通知失败: executionId={}", executionId, e);
            }
        }
        return true;
    }
}
