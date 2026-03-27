package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 质量评估档案实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_quality_profile", autoResultMap = true)
public class QualityProfile extends BaseEntity {

    private String code;
    private String name;
    private String issueType;
    private String description;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> triggerModes;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> enabledDimensionCodes;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> enabledRuleCodes;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> thresholdConfig;

    private String status;
    private Boolean isDefault;
    private Boolean isBuiltin;
    private Integer version;
    private LocalDateTime publishedAt;
}
