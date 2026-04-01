package com.schemaplexai.model.dto.workspace;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 分支规则保存请求
 */
@Data
public class BranchRuleSaveRequest {

    @NotBlank(message = "工作空间不能为空")
    private String workspaceId;

    @NotBlank(message = "目标分支不能为空")
    private String branchPattern;

    @NotBlank(message = "分支命名模板不能为空")
    private String branchNameTemplate;

    private Boolean autoCreateForSpec;

    private Map<String, Object> protectionRules;
}
