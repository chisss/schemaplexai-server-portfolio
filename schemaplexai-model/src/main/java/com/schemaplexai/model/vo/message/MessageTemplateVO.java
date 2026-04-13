package com.schemaplexai.model.vo.message;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息模板视图对象
 */
@Data
public class MessageTemplateVO {

    private String id;

    private String name;

    private String templateType;

    private String status;

    private String titleTemplate;

    private String contentTemplate;

    private String description;

    private List<String> supportedChannels;

    private String createdBy;

    private String createdByName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
