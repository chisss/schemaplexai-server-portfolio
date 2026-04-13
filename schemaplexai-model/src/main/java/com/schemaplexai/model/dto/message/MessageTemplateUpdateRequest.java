package com.schemaplexai.model.dto.message;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新消息模板请求
 */
@Data
public class MessageTemplateUpdateRequest {

    @Size(max = 100, message = "模板名称不能超过100个字符")
    private String name;

    private String templateType;

    @Size(max = 200, message = "标题模板不能超过200个字符")
    private String titleTemplate;

    @Size(max = 12000, message = "正文模板不能超过12000个字符")
    private String contentTemplate;

    private String status;

    private String description;

    private List<String> supportedChannels;
}
