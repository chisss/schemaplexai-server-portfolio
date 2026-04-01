package com.schemaplexai.model.dto.quality;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 质量配置组保存请求
 */
@Data
public class QualityProfileConfigRequest {

    @NotBlank(message = "配置组编码不能为空")
    private String code;

    @NotBlank(message = "配置组名称不能为空")
    private String name;

    private String issueType = "both";
    private String description;
    private List<String> triggerModes = new ArrayList<>();
    private List<String> enabledDimensionCodes = new ArrayList<>();
    private List<String> enabledRuleCodes = new ArrayList<>();
    private Map<String, Object> thresholdConfig;
    private String status;
    private Boolean isDefault;

    @Size(min = 1, max = 2, message = "模型数量必须为1到2个")
    private List<String> modelIds = new ArrayList<>();

    private List<QualityProfileBindingRequest> bindings = new ArrayList<>();
}
