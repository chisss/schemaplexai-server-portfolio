package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建工具审批修正规则请求
 */
@Data
public class AmendmentCreateRequest {

    @NotBlank(message = "agentId不能为空")
    private String agentId;

    @NotBlank(message = "工具编码不能为空")
    private String toolCode;

    /** 命令内容（用于提取匹配模式） */
    private String command;

    /** 作用域: AGENT/TENANT */
    private String scope;

    /** 过期时间（可选） */
    private LocalDateTime expiresAt;
}
