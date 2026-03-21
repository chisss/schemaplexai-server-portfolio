package com.schemaplexai.model.dto.workspace;

import lombok.Data;

/**
 * 工作空间查询请求
 */
@Data
public class WorkspaceQueryRequest {

    /** 当前页码 */
    private Integer page = 1;

    /** 每页大小 */
    private Integer size = 20;

    /** 来源类型 */
    private String sourceType;

    /** 状态 */
    private String workspaceStatus;

    /** Git平台 */
    private String gitPlatform;

    /** 关键字搜索 */
    private String keyword;
}
