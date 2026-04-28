package com.schemaplexai.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 创建用户记忆请求
 */
@Data
public class UserMemoryCreateRequest {

    /** Agent ID，可为空 */
    private String agentId;

    /** 项目ID */
    private String projectId;

    /** 工作区ID */
    private String workspaceId;

    /** 作用域 */
    private String memoryScope;

    /** 记忆类型 */
    private String memoryKind;

    /** 内容 */
    @NotBlank(message = "记忆内容不能为空")
    @Size(max = 2000, message = "记忆内容不能超过2000字")
    private String content;

    /** 结构化值 */
    private Map<String, Object> structuredValue;

    /** 是否置顶 */
    private Boolean pinned;

    /** 过期时间 */
    private LocalDateTime expiresAt;
}
