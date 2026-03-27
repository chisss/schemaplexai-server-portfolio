package com.schemaplexai.service.agent.tool.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.dao.mapper.ToolExecutionLogMapper;
import com.schemaplexai.model.entity.ToolExecutionLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutionLogService {

    private final ToolExecutionLogMapper logMapper;
    private final ObjectMapper objectMapper;

    @Async
    public void logExecution(String tenantId, String agentId, String sessionId, String toolCallId,
                             String toolType, String toolName, String status,
                             LocalDateTime startAt, LocalDateTime endAt,
                             Map<String, Object> request, Map<String, Object> response,
                             String errorMessage) {
        try {
            ToolExecutionLog log = new ToolExecutionLog();
            log.setTenantId(tenantId);
            log.setAgentId(agentId);
            log.setSessionId(sessionId);
            log.setToolCallId(toolCallId);
            log.setToolType(toolType);
            log.setToolName(toolName);
            log.setStatus(status);
            log.setStartAt(startAt);
            log.setEndAt(endAt);
            if (startAt != null && endAt != null) {
                log.setLatencyMs(java.time.Duration.between(startAt, endAt).toMillis());
            }
            log.setRequestPayload(objectMapper.writeValueAsString(request));
            log.setResponsePayload(objectMapper.writeValueAsString(response));
            log.setErrorMessage(errorMessage);
            log.setCreatedAt(LocalDateTime.now());
            logMapper.insert(log);
        } catch (Exception e) {
            log.error("审计日志写入失败: {}", e.getMessage());
        }
    }
}
