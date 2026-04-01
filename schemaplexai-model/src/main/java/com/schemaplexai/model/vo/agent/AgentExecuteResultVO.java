package com.schemaplexai.model.vo.agent;

import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 执行提交结果 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecuteResultVO {

    /** 执行 ID */
    private String executionId;

    /** 会话 ID（用于多轮对话续接） */
    private String conversationId;

    /** 提交状态: queued/failed/blocked/paused */
    private String status;

    /** 入队时间 */
    private LocalDateTime queuedAt;

    /** 结果消息 */
    private String message;

    /** 安全检查决策 */
    private SecurityCheckDecisionVO securityDecision;
}
