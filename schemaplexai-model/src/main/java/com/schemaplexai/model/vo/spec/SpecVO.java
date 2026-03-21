package com.schemaplexai.model.vo.spec;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spec信息VO
 */
@Data
public class SpecVO {

    /** Spec ID */
    private String id;

    /** Spec名称 */
    private String name;

    /** 语义版本号 */
    private String version;

    /** 生命周期状态 */
    private String status;

    /** Spec拥有者 */
    private String owner;

    /** 分类 */
    private String category;

    /** 标签 */
    private List<String> tags;

    /** 描述 */
    private String description;

    /** 关联项目ID（废弃，保留兼容性） */
    @Deprecated
    private String projectId;

    /** 关联工作空间ID列表 */
    private List<String> workspaceIds;

    /** 文档列表 */
    private List<SpecDocumentVO> documents;

    /** 关联工作流模板ID */
    private String workflowId;

    /** 工作流模板名称 */
    private String workflowName;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
