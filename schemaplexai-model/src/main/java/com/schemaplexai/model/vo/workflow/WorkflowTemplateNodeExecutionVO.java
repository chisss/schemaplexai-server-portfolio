package com.schemaplexai.model.vo.workflow;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工作流模板节点执行日志视图对象
 */
@Data
public class WorkflowTemplateNodeExecutionVO {

    /** 节点执行ID */
    private String id;

    /** 工作流实例ID */
    private String instanceId;

    /** 工作流实例名称 */
    private String instanceName;

    /** 关联 Spec ID */
    private String specId;

    /** 节点ID */
    private String nodeId;

    /** 节点类型 */
    private String nodeType;

    /** 节点标签 */
    private String nodeLabel;

    /** 执行状态 */
    private String status;

    /** 输入数据 */
    private Map<String, Object> inputData;

    /** 输出数据 */
    private Map<String, Object> outputData;

    /** 错误信息 */
    private String errorMessage;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
