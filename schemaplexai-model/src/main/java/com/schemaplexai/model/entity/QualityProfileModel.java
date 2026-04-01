package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 质量配置组模型实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sf_quality_profile_model")
public class QualityProfileModel extends BaseEntity {

    private String profileId;
    private String modelId;
    private String modelRole;
    private Integer sortOrder;
}
