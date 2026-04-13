package com.schemaplexai.model.dto.workflow;

import lombok.Data;

/**
 * 评审决策请求
 */
@Data
public class ReviewDecisionRequest {

    /** 备注 */
    private String comment;

    /** 驳回或退回时的回滚节点 */
    private String rollbackToNodeId;

    /** 退回修改说明 */
    private String modifyInstruction;
}
