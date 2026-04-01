package com.schemaplexai.model.vo.quality;

import lombok.Data;

/**
 * 质量配置组绑定视图
 */
@Data
public class QualityProfileBindingVO {

    private String id;
    private String bindingType;
    private String bindingId;
    private String bindingName;
}
