package com.schemaplexai.model.dto.context;

import lombok.Data;

/**
 * 上下文查询请求
 */
@Data
public class ContextQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    private String keyword;

    private String contextLevel;

    private String projectId;

    private String status;
}
