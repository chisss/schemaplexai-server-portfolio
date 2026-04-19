package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户运行时策略 VO
 */
@Data
public class TenantRuntimePolicyVO {

    private String tenantId;
    private String workspaceRootPath;
    private Integer maxWorkspaceGb;
    private Integer maxExecutionMinutes;
    private String sandboxProfile;
    private Boolean allowLocalImport;
    private List<String> sandboxAllowedCommands;
    private LocalDateTime updatedAt;
}
