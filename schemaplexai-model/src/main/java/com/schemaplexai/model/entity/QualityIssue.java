package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 质量问题实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_quality_issue", autoResultMap = true)
public class QualityIssue extends BaseEntity {

    private String specId;
    private String taskId;
    /** deviation / intent_defect / cross_review */
    private String issueType;
    /** workflow / manual / system / agent */
    private String sourceType;
    /** critical / warning / info */
    private String severity;
    /** pass / warn / pause / fail / retry */
    private String gateDecision;
    /** sync / short_wait / async */
    private String executionStrategy;
    private String title;
    private String summary;
    private Integer score;
    private Integer findingCount;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> findingsJson;

    private String workflowInstanceId;
    private String workflowNodeId;
    private String crossReviewId;
    /** open / acknowledged / resolving / resolved / ignored */
    private String status;
    private String assigneeId;
    /** fix / accept_risk / retry / override */
    private String resolutionAction;
    private String resolutionRemark;
    private String resolvedBy;
    private LocalDateTime resolvedAt;
}
