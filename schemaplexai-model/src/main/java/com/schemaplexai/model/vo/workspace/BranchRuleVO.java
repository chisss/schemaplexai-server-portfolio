package com.schemaplexai.model.vo.workspace;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 分支规则视图
 */
@Data
public class BranchRuleVO {

    private String id;
    private String workspaceId;
    private String workspaceName;
    private String branchPattern;
    private String branchNameTemplate;
    private Boolean autoCreateForSpec;
    private Map<String, Object> protectionRules;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
