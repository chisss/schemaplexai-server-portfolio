package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * 工具审批批次快照：暂停时持久化该轮整批 toolCalls 及每个 callId 的状态账本。
 * 状态值：pending / pending_approval / consumed / restored_skipped。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_tool_approval_batch", autoResultMap = true)
public class ToolApprovalBatch extends BaseEntity {

    /** 关联执行 ID */
    private String executionId;

    /** Agent ID */
    private String agentId;

    /** 会话 ID */
    private String conversationId;

    /** Agentic Loop 轮次 */
    private Integer roundNum;

    /** callId 状态账本: [{callId, toolCode, status}] */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> callStates;
}
