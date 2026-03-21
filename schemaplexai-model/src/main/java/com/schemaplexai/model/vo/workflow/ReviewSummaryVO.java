package com.schemaplexai.model.vo.workflow;

import lombok.Data;

/**
 * 评审汇总VO
 */
@Data
public class ReviewSummaryVO {

    private Integer totalComments;

    private Integer criticalCount;

    private Integer warningCount;

    private Integer infoCount;

    private Integer submittedCount;

    private Integer totalReviewers;

    /** 是否存在Critical级别意见 */
    private Boolean hasCritical;
}
