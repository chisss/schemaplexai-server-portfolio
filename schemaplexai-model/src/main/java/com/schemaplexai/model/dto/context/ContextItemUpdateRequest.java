package com.schemaplexai.model.dto.context;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 更新上下文条目请求
 */
@Data
public class ContextItemUpdateRequest {

    @Size(max = 200)
    private String title;

    private String content;

    private String sourceUrl;

    private Map<String, Object> metadata;

    private Integer sortOrder;
}
