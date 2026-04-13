package com.schemaplexai.model.dto.message;

import lombok.Data;

/**
 * 消息模板查询请求
 */
@Data
public class MessageTemplateQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    private String templateType;

    private String status;

    private String name;

    private String createdByKeyword;

    private String keyword;
}
