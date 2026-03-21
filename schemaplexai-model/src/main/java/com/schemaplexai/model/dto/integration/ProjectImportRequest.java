package com.schemaplexai.model.dto.integration;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 从集成配置导入项目请求
 */
@Data
public class ProjectImportRequest {

    /** 外部平台项目ID（Git仓库ID或名称） */
    @NotBlank(message = "外部项目ID不能为空")
    private String externalProjectId;

    /** 外部平台项目名称 */
    @NotBlank(message = "外部项目名称不能为空")
    private String externalProjectName;

    /** 同步配置（分支、路径过滤等） */
    private Map<String, Object> syncConfig;
}
