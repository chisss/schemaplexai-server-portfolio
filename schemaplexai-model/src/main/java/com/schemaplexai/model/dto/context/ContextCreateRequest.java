package com.schemaplexai.model.dto.context;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 创建上下文请求
 */
@Data
public class ContextCreateRequest {

    @NotBlank(message = "名称不能为空")
    @Size(max = 200)
    private String name;

    @NotBlank(message = "上下文层级不能为空")
    @Pattern(regexp = "global|project|task|agent", message = "层级必须为 global/project/task/agent")
    private String contextLevel;

    /** project/task/agent层级必填 */
    private String projectId;

    private String description;

    private Map<String, Object> metadata;

    /** 创建时关联的其他上下文ID列表（用于建立知识图谱连线） */
    private List<String> linkedContextIds = new ArrayList<>();
}
