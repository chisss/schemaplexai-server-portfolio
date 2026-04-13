package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 产物版本实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_artifact_version", autoResultMap = true)
public class ArtifactVersion extends BaseEntity {

    /** 关联产物 ID */
    private String artifactId;

    /** 版本号 */
    private Integer versionNumber;

    /** 版本内容 */
    private String contentText;

    /** 版本元数据 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadataJson;
}
