package com.schemaplexai.model.vo.evaluation;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评估数据集视图
 */
@Data
public class EvalDatasetVO {

    private String id;

    private String name;

    private String description;

    private Integer itemCount;

    private LocalDateTime lastUsedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private List<EvalDatasetItemVO> items;
}
