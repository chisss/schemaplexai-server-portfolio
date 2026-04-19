package com.schemaplexai.model.vo.integration;

import lombok.Data;

/**
 * 集成仓库文件树节点视图对象
 */
@Data
public class IntegrationRepositoryTreeNodeVO {

    /** 节点路径 */
    private String path;

    /** 节点名称 */
    private String name;

    /** 节点类型: directory / file */
    private String type;

    /** 是否叶子节点 */
    private Boolean leaf;
}
