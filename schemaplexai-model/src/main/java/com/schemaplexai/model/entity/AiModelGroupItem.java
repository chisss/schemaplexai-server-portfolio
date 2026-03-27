package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI模型组成员实体（无租户隔离，通过group_id关联隔离）
 */
@Data
@TableName("sf_ai_model_group_item")
public class AiModelGroupItem implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属模型组ID */
    private String groupId;

    /** 模型ID */
    private String modelId;

    /** 排序序号，越小优先级越高（降级顺序） */
    private Integer sortOrder;

    private LocalDateTime createdAt;
}
