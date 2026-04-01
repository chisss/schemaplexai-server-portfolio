package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 安全行业规则项实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_security_rule_item", autoResultMap = true)
public class SecurityRuleItem extends BaseEntity {

    private String packId;

    private String itemCode;

    private String itemName;

    private String ruleSource;

    private String ruleClause;

    private String riskLevel;

    private String action;

    private String matchType;

    private String matchContent;

    private Integer sortOrder;

    private Boolean enabled;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> itemConfig;
}
