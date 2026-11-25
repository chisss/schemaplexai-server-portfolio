package com.schemaplexai.model.vo.desktop;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** 单次投递的本机工具请求；callId 为服务端生成的投递标识。 */
public record DesktopToolCallVO(
        String callId,
        String executionId,
        String parentExecutionId,
        String agentId,
        String deviceId,
        String workspaceId,
        String toolCode,
        JsonNode arguments,
        Instant expiresAt) {
}
