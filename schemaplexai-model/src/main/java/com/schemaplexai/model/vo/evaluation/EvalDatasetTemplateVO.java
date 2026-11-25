package com.schemaplexai.model.vo.evaluation;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 评估数据集内置模板（RAG 场景化等，前端"从模板创建数据集"消费）
 */
@Data
public class EvalDatasetTemplateVO {

    /** 模板 ID（如 rag-qa） */
    private String id;

    /** 模板名称 */
    private String name;

    /** 模板描述 */
    private String description;

    /** 分类：RAG / 通用 */
    private String category;

    /** 建议通过分数线（可空） */
    private Integer suggestedPassScoreThreshold;

    /** 模板条目 */
    private List<TemplateItem> items;

    @Data
    public static class TemplateItem {

        /** 评估输入（用户问题） */
        private String inputText;

        /** 期望输出 */
        private String expectedOutput;

        /** 条目元数据（RAG 模板含 context 检索上下文） */
        private Map<String, Object> metadata;
    }
}
