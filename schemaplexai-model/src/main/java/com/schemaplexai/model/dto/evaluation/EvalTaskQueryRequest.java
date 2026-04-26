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
}
