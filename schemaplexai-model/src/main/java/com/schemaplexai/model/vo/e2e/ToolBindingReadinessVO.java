package com.schemaplexai.model.vo.e2e;

import lombok.Data;

/**
 * 工具绑定准备度
 */
@Data
public class ToolBindingReadinessVO {

    private String bindingId;

    private String toolCode;

    private String sourceType;

    private String sourceRefId;

    private String status;

    private String message;
}
