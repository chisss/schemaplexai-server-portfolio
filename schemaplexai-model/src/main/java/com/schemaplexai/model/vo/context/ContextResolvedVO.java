package com.schemaplexai.model.vo.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 上下文解析结果VO - 四层合并后的完整上下文
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContextResolvedVO {

    /** 合并后的上下文文本 */
    private String mergedContent;

    /** 实际使用的Token数 */
    private int usedTokens;

    /** Token预算上限 */
    private int tokenBudget;

    /** 各层上下文详情 */
    private List<ContextLayer> layers;

    /**
     * 上下文层
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContextLayer {
        /** 层级: global/project/task/agent */
        private String level;
        /** 层级标题 */
        private String title;
        /** 层级内容 */
        private String content;
        /** 该层使用的Token数 */
        private int tokenCount;
        /** 分配的Token预算 */
        private int tokenBudget;
        /** 条目数 */
        private int itemCount;
    }
}
