package com.schemaplexai.model.dto.cicd;

import lombok.Data;

/**
 * CICD Pipeline查询请求
 */
@Data
public class CicdPipelineQueryRequest {

    /** 当前页码 */
    private Integer page = 1;

    /** 每页大小 */
    private Integer size = 20;

    /** Pipeline类型 */
    private String pipelineType;

    /** 状态 */
    private String status;

    /** 关联工作空间ID */
    private String workspaceId;

    /** 关键字搜索 */
    private String keyword;
}
