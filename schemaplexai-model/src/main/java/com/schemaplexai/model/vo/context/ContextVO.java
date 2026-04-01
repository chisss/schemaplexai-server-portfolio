package com.schemaplexai.model.vo.context;

import com.schemaplexai.model.vo.context.ContextRelationVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 上下文VO
 */
@Data
public class ContextVO {

    private String id;

    private String name;

    private String contextLevel;

    private String projectId;

    private String description;

    private String status;

    private Map<String, Object> metadata;

    /** 条目数量（汇总字段） */
    private Integer itemCount;

    /** Token总量（汇总字段） */
    private Integer totalTokens;

    /** 当前上下文是否已经完成过向量化 */
    private Boolean vectorized;

    /** 向量状态: none/processing/failed/completed */
    private String vectorStatus;

    /** 向量分块总数 */
    private Integer vectorChunkCount;

    /** 已上传知识文档数量 */
    private Integer knowledgeDocumentCount;

    /** 最近一次向量更新时间 */
    private LocalDateTime vectorUpdatedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 关联关系列表（可选，详情页使用） */
    private List<ContextRelationVO> relations;
}
