package com.schemaplexai.model.vo.integration;

import lombok.Data;

/**
 * 集成仓库视图对象
 */
@Data
public class IntegrationRepositoryVO {

    /** 远端仓库ID */
    private String id;

    /** 仓库名称 */
    private String name;

    /** 带命名空间的全名 */
    private String fullName;

    /** 仓库描述 */
    private String description;

    /** 默认分支 */
    private String defaultBranch;

    /** HTTP 克隆地址 */
    private String httpUrl;

    /** SSH 克隆地址 */
    private String sshUrl;
}
