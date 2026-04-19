package com.schemaplexai.service.integration.platform;

import lombok.Builder;
import lombok.Data;

/**
 * 远程项目信息
 */
@Data
@Builder
public class RemoteProject {

    /** 远程平台的项目 ID */
    private String remoteId;
    /** 完整名称（如 owner/repo） */
    private String fullName;
    private String name;
    private String description;
    private String defaultBranch;
    private String httpUrl;
    private String sshUrl;
}
