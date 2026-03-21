package com.schemaplexai.model.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 创建评审会话请求
 */
@Data
public class ReviewSessionCreateRequest {

    @NotBlank(message = "Spec ID不能为空")
    private String specId;

    @NotBlank(message = "文档类型不能为空")
    private String documentType;

    /** 评审人列表 [{userId, role}] */
    @NotEmpty(message = "评审人不能为空")
    private List<Map<String, String>> reviewers;

    /** 截止时间（小时），默认48 */
    private Integer deadlineHours = 48;

    /** 超时策略: auto_pass/escalate/remind */
    private String timeoutStrategy = "escalate";
}
