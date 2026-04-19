package com.schemaplexai.service.integration.platform;

import lombok.Builder;
import lombok.Data;

/**
 * 远端仓库文件树节点
 */
@Data
@Builder
public class RemoteRepositoryTreeNode {

    /** 节点路径 */
    private String path;

    /** 节点名称 */
    private String name;

    /** 节点类型: directory / file */
    private String type;

    /** 是否叶子节点 */
    private boolean leaf;
}
