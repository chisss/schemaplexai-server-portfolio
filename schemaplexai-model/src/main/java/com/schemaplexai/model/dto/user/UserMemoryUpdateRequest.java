package com.schemaplexai.model.dto.user;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 更新用户记忆请求
 */
@Data
public class UserMemoryUpdateRequest {

    /** 作用域 */
    private String memoryScope;

    /** 记忆类型 */
    private String memoryKind;

    /** 状态 */
    private String status;

    /** 内容 */
    @Size(max = 2000, message = "记忆内容不能超过2000字")
    private String content;

    /** 结构化值 */
    private Map<String, Object> structuredValue;

    /** 是否置顶 */
    private Boolean pinned;

    /** 过期时间 */
    private LocalDateTime expiresAt;
}
