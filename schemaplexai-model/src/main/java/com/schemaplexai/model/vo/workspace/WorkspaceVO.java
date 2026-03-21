package com.schemaplexai.model.vo.workspace;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工作空间视图对象
 */
@Data
public class WorkspaceVO {

    /** 主键ID */
    private String id;

    /** 工作空间名称 */
    private String name;

    /** 来源类型: git/local/manual */
    private String sourceType;

    /** Git仓库地址 */
    private String gitUrl;

    /** Git平台 */
    private String gitPlatform;

    /** 默认分支 */
    private String defaultBranch;

    /** 本地路径 */
    private String localPath;

    /** 状态 */
    private String workspaceStatus;

    /** 磁盘占用(MB) */
    private Long diskUsageMb;

    /** 最近同步时间 */
    private LocalDateTime lastSyncAt;

    /** 错误信息 */
    private String errorMessage;

    /** 描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 创建人名称 */
    private String createdByName;
}
