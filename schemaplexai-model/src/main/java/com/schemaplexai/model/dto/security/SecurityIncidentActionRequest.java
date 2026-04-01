package com.schemaplexai.model.dto.security;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 安全事件动作请求
 */
@Data
public class SecurityIncidentActionRequest {

    private String assigneeId;

    private String assigneeName;

    @NotBlank(message = "处置说明不能为空")
    private String comment;
}
