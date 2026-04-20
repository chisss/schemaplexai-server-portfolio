package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 对话回滚请求
 */
@Data
public class ConversationRollbackRequest {

    @NotNull(message = "目标轮次索引不能为空")
    @Min(value = 0, message = "目标轮次索引不能为负数")
    private Integer targetTurnIndex;
}
