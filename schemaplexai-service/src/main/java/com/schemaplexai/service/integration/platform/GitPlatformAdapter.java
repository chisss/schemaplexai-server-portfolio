package com.schemaplexai.service.integration.platform;

import java.util.List;
import java.util.Map;

/**
 * Git 平台适配器接口
 * <p>
 * 每个 Git 平台（GitHub/GitLab/Gitee/Bitbucket）实现此接口。
 * </p>
 */
public interface GitPlatformAdapter {

    /** 平台标识，对应 {@link com.schemaplexai.common.enums.GitPlatformEnum#getCode()} */
    String platform();

    /** 连通性测试 */
    ConnectionTestResult testConnection(Map<String, Object> config);

    /** 同步远程项目列表 */
    List<RemoteProject> syncProjects(Map<String, Object> config);

    /** 列出远端仓库 */
    default List<RemoteProject> listRepositories(Map<String, Object> config) {
        return syncProjects(config);
    }

    /** 获取远端仓库文件树 */
    default List<RemoteRepositoryTreeNode> getRepositoryTree(Map<String, Object> config,
                                                             String repositoryId,
                                                             String ref,
                                                             String path) {
        throw new UnsupportedOperationException("当前平台暂不支持读取仓库文件树");
    }

    /** 创建合并请求 */
    RemoteMergeRequest createMergeRequest(Map<String, Object> config,
                                          String projectIdentifier,
                                          String sourceBranch,
                                          String targetBranch,
                                          String title,
                                          String description);

    /** 查询合并请求状态 */
    RemoteMergeRequest getMergeRequestStatus(Map<String, Object> config,
                                             String projectIdentifier,
                                             String remoteMrId);
}
