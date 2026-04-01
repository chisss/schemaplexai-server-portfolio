package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 安全行业规则包实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_security_rule_pack", autoResultMap = true)
public class SecurityRulePack extends BaseEntity {

    private String packCode;

    private String packName;

    private String industryCode;

    private String status;

    private Integer packVersion;

    private String defaultAction;

    private Boolean isBuiltin;

    private String description;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> packConfig;
}
