package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 质量评估维度实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_quality_dimension", autoResultMap = true)
public class QualityDimension extends BaseEntity {

    private String code;
    private String name;
    private String issueType;
    private String description;
    private Integer sortOrder;
    private String status;
    private Boolean isBuiltin;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extConfig;
}
