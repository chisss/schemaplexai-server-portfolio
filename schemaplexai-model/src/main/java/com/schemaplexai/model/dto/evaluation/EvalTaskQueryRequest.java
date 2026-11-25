package com.schemaplexai.model.dto.evaluation;

import lombok.Data;

/**
 * 评估任务查询请求
 */
@Data
public class EvalTaskQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    private String status;

    /** 数据集 ID 过滤（任务间纵向对比数据入口） */
    private String datasetId;
}
