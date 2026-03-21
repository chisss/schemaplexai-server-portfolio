package com.schemaplexai.model.vo.integration;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 集成配置视图对象
 */
@Data
public class IntegrationVO {

    /** 主键ID */
    private String id;

    /** 集成类型: git/cicd/pm/im */
    private String integrationType;

    /** 平台: github/gitlab/gitee/jenkins/... */
    private String platform;

    /** 名称 */
    private String name;

    /** 脱敏后的配置信息 */
    private Map<String, Object> config;

    /** 状态: active/inactive */
    private String status;

    /** 最近同步时间 */
    private LocalDateTime lastSyncAt;

    /** 错误信息 */
    private String errorMessage;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 创建人名称 */
    private String createdByName;
}
