package com.schemaplexai.model.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建消息模板请求
 */
@Data
public class MessageTemplateCreateRequest {

    @NotBlank(message = "模板名称不能为空")
    @Size(max = 100, message = "模板名称不能超过100个字符")
    private String name;

    @NotBlank(message = "模板类型不能为空")
    private String templateType;

    @NotBlank(message = "标题模板不能为空")
    @Size(max = 200, message = "标题模板不能超过200个字符")
    private String titleTemplate;

    @NotBlank(message = "正文模板不能为空")
    @Size(max = 12000, message = "正文模板不能超过12000个字符")
    private String contentTemplate;

    private String status;

    private String description;

    private List<String> supportedChannels;
}
