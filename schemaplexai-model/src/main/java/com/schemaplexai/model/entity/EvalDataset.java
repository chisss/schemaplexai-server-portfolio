package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 模型评估数据集
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sf_eval_dataset")
public class EvalDataset extends BaseEntity {

    /**
     * 数据集名称
     */
    private String name;

    /**
     * 数据集描述
     */
    private String description;

    /**
     * 最近使用时间
     */
    private LocalDateTime lastUsedAt;
}
