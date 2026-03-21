package com.schemaplexai.model.dto.workspace;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 更新工作空间请求
 */
@Data
public class WorkspaceUpdateRequest {

    /** 工作空间名称 */
    @Size(max = 100, message = "名称不能超过100个字符")
    private String name;

    /** 默认分支 */
    private String defaultBranch;

    /** 描述 */
    private String description;

    /** Git凭证 */
    private Map<String, Object> gitCredential;
}
