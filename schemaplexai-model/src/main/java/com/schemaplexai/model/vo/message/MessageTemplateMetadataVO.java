package com.schemaplexai.model.vo.message;

import lombok.Data;

import java.util.List;

/**
 * 消息模板元数据
 */
@Data
public class MessageTemplateMetadataVO {

    private String templateType;

    private String templateName;

    private String defaultTitleTemplate;

    private String defaultContentTemplate;

    private List<MessageTemplateVariableVO> supportedVariables;
}
