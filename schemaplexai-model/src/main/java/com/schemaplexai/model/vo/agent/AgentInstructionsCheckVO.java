package com.schemaplexai.model.vo.agent;

import lombok.Builder;
import lombok.Data;

/**
 * Agent 专属指令检测结果 VO
 */
@Data
@Builder
public class AgentInstructionsCheckVO {

    /** 是否已配置专属指令 */
    private boolean hasInstructions;

    /** 专属指令条目 ID（存在时返回） */
    private String itemId;

    /** 所属上下文 ID */
    private String contextId;

    /** 指令标题 */
    private String title;
}
