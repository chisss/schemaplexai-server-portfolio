package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI模型组VO
 */
@Data
public class AiModelGroupVO {

    private String id;
    private String name;
    private String description;
    private String routingStrategy;
    private String useCase;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 成员列表（按 sortOrder 升序） */
    private List<AiModelGroupItemVO> items;

    @Data
    public static class AiModelGroupItemVO {
        private String id;
        private String modelId;
        /** 模型显示名称（从 sf_ai_model 关联） */
        private String modelName;
        /** 模型提供商 */
        private String provider;
        /** 模型用途 */
        private String useCase;
        private Integer sortOrder;
    }
}
