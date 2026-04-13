package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 统一产物实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_artifact", autoResultMap = true)
public class Artifact extends BaseEntity {

    /** 关联 Spec ID */
    private String specId;

    /** 关联工作流实例 ID */
    private String workflowInstanceId;

    /** 关联工作空间 ID */
    private String workspaceId;

    /** 产物名称 */
    private String name;

    /** 产物标题 */
    private String title;

    /** 产物类型: document/marketing_copy_bundle/image/video/external_link */
    private String artifactType;

    /** 产物格式: markdown/json/text/html */
    private String format;

    /** MIME 类型 */
    private String mimeType;

    /** 最新版本号 */
    private Integer latestVersion;

    /** 来源节点 ID */
    private String sourceNodeId;

    /** 主体内容 */
    private String contentText;

    /** 扩展元数据 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadataJson;
}
