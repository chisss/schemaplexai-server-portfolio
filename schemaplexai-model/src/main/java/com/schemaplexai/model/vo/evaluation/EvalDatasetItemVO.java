package com.schemaplexai.model.vo.evaluation;

import lombok.Data;

import java.util.Map;

/**
 * 评估数据集条目视图
 */
@Data
public class EvalDatasetItemVO {

    private String id;

    private String inputText;

    private String expectedOutput;

    private Map<String, Object> metadata;

    private Integer sortOrder;
}
