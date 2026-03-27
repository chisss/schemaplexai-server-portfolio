package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 租户运行时策略更新请求
 */
@Data
public class TenantRuntimePolicyUpdateRequest {

    private String workspaceRootPath;

    @Min(value = 1, message = "maxWorkspaceGb 最小为1")
    @Max(value = 2048, message = "maxWorkspaceGb 最大为2048")
    private Integer maxWorkspaceGb;

    @Min(value = 1, message = "maxExecutionMinutes 最小为1")
    @Max(value = 1440, message = "maxExecutionMinutes 最大为1440")
    private Integer maxExecutionMinutes;

    @Pattern(regexp = "strict|standard|permissive", message = "sandboxProfile 仅支持 strict/standard/permissive")
    private String sandboxProfile;

    private Boolean allowLocalImport;
}
