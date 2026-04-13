package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工作空间实体
 */
@Data
@TableName(value = "sf_workspace", autoResultMap = true)
public class Workspace implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    private String tenantId;

    /** 工作空间名称 */
    private String name;

    /** 来源类型: git/local/manual */
    private String sourceType;

    /** Git仓库地址 */
    private String gitUrl;

    /** Git平台: github/gitlab/gitee */
    private String gitPlatform;

    /** Git凭证(加密存储) */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> gitCredential;

    /** 默认分支 */
    private String defaultBranch;

    /** 本地克隆路径 */
    private String localPath;

    /** 访问模式: managed/server_path/reference_only */
    private String accessMode;

    /** 写入能力: writable/read_only */
    private String writeCapability;

    /** 浏览能力: browsable/hidden */
    private String browseCapability;

    /** 下载能力: downloadable/none */
    private String downloadCapability;

    /** 状态: cloning/ready/syncing/error/archived */
    private String workspaceStatus;

    /** 磁盘占用(MB) */
    private Long diskUsageMb;

    /** 最近同步时间 */
    private LocalDateTime lastSyncAt;

    /** 错误信息 */
    private String errorMessage;

    /** 描述 */
    private String description;

    /** 工作空间作用域: PROJECT/SYSTEM */
    private String workspaceScope;

    /** 保留策略: retain/ephemeral */
    private String retentionPolicy;

    /** 创建人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新人 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
