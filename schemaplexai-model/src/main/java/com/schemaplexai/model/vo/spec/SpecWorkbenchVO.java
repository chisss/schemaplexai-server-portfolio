package com.schemaplexai.model.vo.spec;

import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import lombok.Data;

import java.util.List;

/**
 * Spec 工作流工作台视图
 */
@Data
public class SpecWorkbenchVO {

    private SpecVO spec;

    private WorkflowInstanceVO workflowInstance;

    private List<SpecWorkbenchNodeVO> nodes;
}
