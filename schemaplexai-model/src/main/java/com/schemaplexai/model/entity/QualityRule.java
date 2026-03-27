package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 质量评估规则实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_quality_rule", autoResultMap = true)
public class QualityRule extends BaseEntity {

    private String dimensionId;
    private String dimensionCode;
    private String code;
    private String name;
    private String ruleType;
    private String triggerMode;
    private String severity;
    private Integer weight;
    private String conditionExpr;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> ruleConfig;

    private String status;
    private Boolean isBuiltin;
}
