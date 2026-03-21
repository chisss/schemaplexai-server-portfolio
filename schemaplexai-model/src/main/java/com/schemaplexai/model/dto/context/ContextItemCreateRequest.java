package com.schemaplexai.model.dto.context;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 创建上下文条目请求
 */
@Data
public class ContextItemCreateRequest {

    @NotBlank(message = "条目类型不能为空")
    @Pattern(regexp = "document|code|config|api", message = "类型必须为 document/code/config/api")
    private String itemType;

    @NotBlank(message = "标题不能为空")
    @Size(max = 200)
    private String title;

    private String content;

    private String sourceUrl;

    private Map<String, Object> metadata;

    private Integer sortOrder;
}
