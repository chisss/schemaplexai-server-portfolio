package com.schemaplexai.model.vo.evaluation;

import lombok.Data;

import java.util.List;

/**
 * 评估任务结果条目视图
 */
@Data
public class EvalTaskResultItemVO {

    private String itemId;

    private String inputText;

    private String expectedOutput;

    private List<EvalTaskItemModelResultVO> modelResults;
}
