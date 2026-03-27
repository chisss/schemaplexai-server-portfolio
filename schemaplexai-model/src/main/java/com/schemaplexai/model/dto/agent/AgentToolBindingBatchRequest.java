package com.schemaplexai.model.dto.agent;

import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;

/**
 * Agent 工具绑定批量更新请求
 */
@Data
public class AgentToolBindingBatchRequest {

    @Valid
    private List<AgentToolBindingDTO> tools;
}
