package com.schemaplexai.model.vo.integration;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 集成项目关联视图对象
 */
@Data
public class IntegrationProjectVO {

    /** 主键ID */
    private String id;

    /** 集成配置ID */
    private String integrationId;

    /** 项目ID */
    private String projectId;

    /** 外部平台项目ID */
    private String externalProjectId;

    /** 外部平台项目名称 */
    private String externalProjectName;

    /** 同步配置 */
    private Map<String, Object> syncConfig;

    /** 状态 */
    private String status;

    /** 最近同步时间 */
    private LocalDateTime lastSyncAt;
}
