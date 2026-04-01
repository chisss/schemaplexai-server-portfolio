package com.schemaplexai.model.vo.quality;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 质量配置组视图
 */
@Data
public class QualityProfileConfigVO {

    private String id;
    private String code;
    private String name;
    private String issueType;
    private String description;
    private List<String> triggerModes = new ArrayList<>();
    private List<String> enabledDimensionCodes = new ArrayList<>();
    private List<String> enabledRuleCodes = new ArrayList<>();
    private Map<String, Object> thresholdConfig;
    private String status;
    private Boolean isDefault;
    private Integer version;
    private List<String> modelIds = new ArrayList<>();
    private List<String> modelNames = new ArrayList<>();
    private List<QualityProfileBindingVO> bindings = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
