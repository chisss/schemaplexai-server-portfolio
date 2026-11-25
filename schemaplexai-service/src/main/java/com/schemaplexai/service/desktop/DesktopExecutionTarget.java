package com.schemaplexai.service.desktop;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.entity.AgentExecution;
import org.springframework.util.StringUtils;

import java.util.Map;

/** 显式本机执行目标，既有云端执行未提供目标时保持原路径。 */
public record DesktopExecutionTarget(String deviceId, String workspaceId) {

    public static final String DEVICE_CONTEXT_KEY = "desktopDeviceId";
    public static final String WORKSPACE_CONTEXT_KEY = "desktopWorkspaceId";

    public static DesktopExecutionTarget from(Map<String, Object> context) {
        if (context == null || (!context.containsKey(DEVICE_CONTEXT_KEY)
                && !context.containsKey(WORKSPACE_CONTEXT_KEY))) {
            return null;
        }
        Object device = context.get(DEVICE_CONTEXT_KEY);
        Object workspace = context.get(WORKSPACE_CONTEXT_KEY);
        if (!(device instanceof String deviceId) || !StringUtils.hasText(deviceId)
                || !(workspace instanceof String workspaceId) || !StringUtils.hasText(workspaceId)
                || deviceId.length() > 100 || workspaceId.length() > 200) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "本机执行必须指定有效的设备和工作目录授权标识");
        }
        return new DesktopExecutionTarget(deviceId, workspaceId);
    }

    public static boolean workspaceConversation(String id) {
        return id != null && (id.startsWith(DesktopConversationService.CONVERSATION_PREFIX)
                || id.contains(":" + DesktopConversationService.CONVERSATION_PREFIX));
    }

    public static void requireLegacyConversation(String id) {
        if (workspaceConversation(id)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "请使用员工工作区的会话接口");
        }
    }

    public static void requireOwner(AgentExecution execution, String userId) {
        if ((workspaceConversation(execution.getConversationId())
                || from(execution.getInputContext()) != null)
                && (!StringUtils.hasText(userId) || !userId.equals(execution.getCreatedBy()))) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅任务所属用户可访问或操作桌面执行任务");
        }
    }
}
