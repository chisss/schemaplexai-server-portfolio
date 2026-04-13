package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 产物投递实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_artifact_delivery", autoResultMap = true)
public class ArtifactDelivery extends BaseEntity {

    /** 关联产物 ID */
    private String artifactId;

    /** 关联工作空间 ID */
    private String workspaceId;

    /** 投递类型: workspace_file/platform_download/external_link */
    private String deliveryType;

    /** 目标路径 */
    private String targetPath;

    /** 目标 URI */
    private String targetUri;

    /** 投递状态 */
    private String deliveryStatus;

    /** 投递说明 */
    private String message;

    /** 投递时间 */
    private LocalDateTime deliveredAt;

    /** 扩展元数据 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadataJson;
}
