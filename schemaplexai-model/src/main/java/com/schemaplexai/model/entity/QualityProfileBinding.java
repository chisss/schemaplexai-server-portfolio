package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 质量配置组绑定实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sf_quality_profile_binding")
public class QualityProfileBinding extends BaseEntity {

    private String profileId;
    private String bindingType;
    private String bindingId;
}
