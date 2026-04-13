package com.schemaplexai.model.vo.spec;

import com.schemaplexai.model.vo.workflow.ReviewSessionVO;
import lombok.Data;

import java.util.Map;

/**
 * Spec 工作台节点视图
 */
@Data
public class SpecWorkbenchNodeVO {

    private String nodeId;

    private String nodeType;

    private String nodeLabel;

    private String status;

    private Boolean current;

    private Map<String, Object> config;

    private SpecDocumentVO document;

    private ReviewSessionVO reviewSession;

    private Map<String, Object> outputData;

    private String errorMessage;
}
