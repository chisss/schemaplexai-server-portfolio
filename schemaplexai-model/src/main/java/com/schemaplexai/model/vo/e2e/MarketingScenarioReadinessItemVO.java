package com.schemaplexai.model.vo.e2e;

import lombok.Data;

import java.util.List;

/**
 * 营销单场景准备度
 */
@Data
public class MarketingScenarioReadinessItemVO {

    private String scenarioCode;

    private String scenarioName;

    private String documentPath;

    private String workflowTemplateName;

    private String workflowTemplateStatus;

    private String agentStatus;

    private String toolBindingStatus;

    private Boolean blocking;

    private String suggestion;

    private List<ToolBindingReadinessVO> toolBindings;
}
