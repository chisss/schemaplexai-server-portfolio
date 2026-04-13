package com.schemaplexai.model.vo.spec;

import com.schemaplexai.model.vo.artifact.ArtifactVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    /** 优先级 */
    private String priority;

    /** Spec拥有者 */
    private String owner;

    /** Spec拥有者名称 */
    private String ownerName;

    /** 创建人 */
    private String createdBy;

    /** 创建人名称 */
    private String createdByName;

    /** 分类 */
    private String category;

    /** 需求类型 */
    private String specType;

    /** 标签 */
    private List<String> tags;

    /** 描述 */
    private String description;

    /** 扩展画像 */
    private Map<String, Object> profileData;

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

    /** 关联工作流实例ID */
    private String workflowInstanceId;

    /** 生命周期模式 */
    private String lifecycleMode;

    /** 当前节点ID */
    private String currentNodeId;

    /** 当前节点类型 */
    private String currentNodeType;

    /** 当前节点标签 */
    private String currentNodeLabel;

    /** 工作流状态快照 */
    private String workflowStatusSnapshot;

    /** Jira 或需求单号 */
    private String jiraTicket;

    /** 目标研发分支 */
    private String targetBranch;

    /** 工作流产出的文档路径 */
    private String artifactDocPath;

    /** 主产物 ID */
    private String primaryArtifactId;

    /** 主产物摘要 */
    private ArtifactVO primaryArtifact;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
