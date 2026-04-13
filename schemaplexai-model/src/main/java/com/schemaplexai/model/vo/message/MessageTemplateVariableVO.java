package com.schemaplexai.model.vo.message;

import lombok.Data;

/**
 * 消息模板变量定义
 */
@Data
public class MessageTemplateVariableVO {

    private String key;

    private String label;

    private String description;

    private String sampleValue;
}
