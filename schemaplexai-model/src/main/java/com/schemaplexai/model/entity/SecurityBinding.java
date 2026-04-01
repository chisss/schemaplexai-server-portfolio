package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 安全绑定关系实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_security_binding", autoResultMap = true)
public class SecurityBinding extends BaseEntity {

    private String sourceType;

    private String sourceId;

    private String sourceCode;

    private String sourceName;

    private String bindingType;

    private String targetId;

    private String targetName;

    private String domainCode;

    private String status;

    private Integer priority;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> bindingConfig;
}
