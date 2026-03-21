package com.schemaplexai.model.dto.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 创建工作空间请求
 */
@Data
public class WorkspaceCreateRequest {

    /** 工作空间名称 */
    @NotBlank(message = "工作空间名称不能为空")
    @Size(max = 100, message = "名称不能超过100个字符")
    private String name;

    /** 来源类型: git/local/manual */
    @NotBlank(message = "来源类型不能为空")
    private String sourceType;

    /** Git仓库地址 */
    private String gitUrl;

    /** Git平台: github/gitlab/gitee */
    private String gitPlatform;

    /** Git凭证 */
    private Map<String, Object> gitCredential;

    /** 默认分支 */
    private String defaultBranch;

    /** 本地路径(local导入时必填) */
    private String localPath;

    /** 描述 */
    private String description;
}
