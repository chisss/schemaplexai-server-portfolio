package com.schemaplexai.model.dto.context;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 更新上下文请求
 */
@Data
public class ContextUpdateRequest {

    @Size(max = 200)
    private String name;

    private String description;

    private String status;

    private Map<String, Object> metadata;
}
