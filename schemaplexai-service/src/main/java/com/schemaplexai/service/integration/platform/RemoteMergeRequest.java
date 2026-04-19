package com.schemaplexai.service.integration.platform;

import lombok.Builder;
import lombok.Data;

/**
 * 远程合并请求信息
 */
@Data
@Builder
public class RemoteMergeRequest {

    /** 远程平台的 MR/PR ID */
    private String remoteMrId;
    /** 浏览器可访问的 URL */
    private String webUrl;
    /** 状态（使用 MergeRequestStatusEnum.code） */
    private String status;
    private String sourceBranch;
    private String targetBranch;
}
