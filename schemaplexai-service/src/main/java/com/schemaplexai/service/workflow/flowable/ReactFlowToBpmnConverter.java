package com.schemaplexai.service.workflow.flowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.*;
import org.flowable.bpmn.model.Process;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * ReactFlow nodes/edges → Flowable BpmnModel 转换器
 *
 * <p>将前端 ReactFlow 可视化编辑器产生的 definition JSON 转换为 Flowable 引擎可部署的 BpmnModel。
 * <ul>
 *   <li>start/trigger_manual → StartEvent</li>
 *   <li>agent/document → ReceiveTask（异步等待外部信号）</li>
 *   <li>human_review → UserTask</li>
 *   <li>notification/deviation_analysis/quality_report/script/api_call/database → ServiceTask</li>
 *   <li>condition → ExclusiveGateway</li>
 *   <li>parallel → ParallelGateway</li>
 *   <li>end → EndEvent</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactFlowToBpmnConverter {

    private static final String NAMESPACE = "http://schemaplexai.com/bpmn";
    private static final String SERVICE_TASK_DELEGATE = "${workflowServiceTaskDelegate}";
    private static final String RECEIVE_TASK_LISTENER = "workflowReceiveTaskListener";
    private static final String END_EVENT_LISTENER = "workflowEndEventListener";
    private static final String HUMAN_REVIEW_TASK_LISTENER = "humanReviewTaskListener";

    private final ObjectMapper objectMapper;

    /**
     * 将 ReactFlow definition 转换为 BpmnModel
     *
     * @param templateId  模板ID，用作流程定义 key
     * @param templateName 模板名称，用作流程定义 name
     * @param definition  ReactFlow 的 {nodes: [...], edges: [...]}
     * @return Flowable BpmnModel
     */
    @SuppressWarnings("unchecked")
    public BpmnModel convert(String templateId, String templateName, Map<String, Object> definition) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) definition.getOrDefault("nodes", List.of());
        List<Map<String, Object>> edges = (List<Map<String, Object>>) definition.getOrDefault("edges", List.of());

        BpmnModel model = new BpmnModel();
        model.setTargetNamespace(NAMESPACE);

        Process process = new Process();
        // 流程 key 中不允许含 '-'，替换为 '_'
        process.setId("sf_" + templateId.replace("-", "_"));
        process.setName(templateName);
        process.setExecutable(true);
        model.addProcess(process);

        // 转换节点
        for (Map<String, Object> node : nodes) {
            FlowElement element = convertNode(node);
            if (element != null) {
                process.addFlowElement(element);
            }
        }

        // 转换边
        for (Map<String, Object> edge : edges) {
            SequenceFlow flow = convertEdge(edge, nodes);
            if (flow != null) {
                process.addFlowElement(flow);
            }
        }

        log.info("BPMN 转换完成: templateId={}, nodes={}, edges={}, processKey={}",
                templateId, nodes.size(), edges.size(), process.getId());
        return model;
    }

    // ===== 节点转换 =====

    private FlowElement convertNode(Map<String, Object> node) {
        String nodeId = str(node, "id");
        String nodeType = str(node, "type");
        String nodeLabel = str(node, "label");
        Map<String, Object> config = getConfig(node);

        if (!StringUtils.hasText(nodeId) || !StringUtils.hasText(nodeType)) {
            return null;
        }

        return switch (nodeType) {
            case "start", "trigger_manual" -> createStartEvent(nodeId, nodeLabel);
            case "trigger_cron" -> createTimerStartEvent(nodeId, nodeLabel, config);
            case "trigger_event" -> createSignalStartEvent(nodeId, nodeLabel, config);
            case "agent", "document" -> createReceiveTask(nodeId, nodeLabel, nodeType, config);
            case "human_review" -> createUserTask(nodeId, nodeLabel, config);
            case "notification", "deviation_analysis", "quality_report",
                 "script", "api_call", "database" -> createServiceTask(nodeId, nodeLabel, nodeType, config);
            case "condition" -> createExclusiveGateway(nodeId, nodeLabel);
            case "parallel" -> createParallelGateway(nodeId, nodeLabel);
            case "loop" -> createServiceTask(nodeId, nodeLabel, nodeType, config); // 简化处理
            case "end" -> createEndEvent(nodeId, nodeLabel);
            default -> {
                log.warn("未知节点类型，按 ServiceTask 处理: nodeId={}, nodeType={}", nodeId, nodeType);
                yield createServiceTask(nodeId, nodeLabel, nodeType, config);
            }
        };
    }

    private StartEvent createStartEvent(String id, String label) {
        StartEvent event = new StartEvent();
        event.setId(id);
        event.setName(label);
        return event;
    }

    private StartEvent createTimerStartEvent(String id, String label, Map<String, Object> config) {
        StartEvent event = new StartEvent();
        event.setId(id);
        event.setName(label);

        // trigger_cron 在 SchemaPlexAI 中由应用层调度触发，不依赖 Flowable Timer
        // 仅创建普通 StartEvent，cron 调度由业务代码处理
        return event;
    }

    private StartEvent createSignalStartEvent(String id, String label, Map<String, Object> config) {
        StartEvent event = new StartEvent();
        event.setId(id);
        event.setName(label);

        String signalName = str(config, "eventName");
        if (StringUtils.hasText(signalName)) {
            SignalEventDefinition signalDef = new SignalEventDefinition();
            signalDef.setSignalRef(signalName);
            event.addEventDefinition(signalDef);
        }
        return event;
    }

    /**
     * agent/document 节点 → ReceiveTask
     * <p>流程到达时自动停住，等待外部 trigger() 信号。
     * 通过 ExecutionListener(start) 触发 WorkflowNodeEngine 的异步执行逻辑。
     */
    private ReceiveTask createReceiveTask(String id, String label, String nodeType, Map<String, Object> config) {
        ReceiveTask task = new ReceiveTask();
        task.setId(id);
        task.setName(label);

        // 添加 start ExecutionListener，触发异步节点执行
        FlowableListener listener = new FlowableListener();
        listener.setEvent("start");
        listener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        listener.setImplementation("${" + RECEIVE_TASK_LISTENER + "}");
        task.setExecutionListeners(List.of(listener));

        // 存储自定义属性到扩展元素
        addExtensionElements(task, id, nodeType, label, config);
        return task;
    }

    /**
     * human_review → UserTask
     */
    private UserTask createUserTask(String id, String label, Map<String, Object> config) {
        UserTask task = new UserTask();
        task.setId(id);
        task.setName(label);
        task.setFormKey("human_review");

        // 设置审批人
        Object reviewerIds = config.get("reviewerIds");
        if (reviewerIds instanceof List<?> ids && !ids.isEmpty()) {
            task.setCandidateUsers(ids.stream().map(String::valueOf).toList());
        }
        Object reviewerRoles = config.get("reviewerRoles");
        if (reviewerRoles instanceof List<?> roles && !roles.isEmpty()) {
            task.setCandidateGroups(roles.stream().map(String::valueOf).toList());
        }

        // 添加 TaskListener(create)，触发审批初始化
        FlowableListener listener = new FlowableListener();
        listener.setEvent("create");
        listener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        listener.setImplementation("${" + HUMAN_REVIEW_TASK_LISTENER + "}");
        task.setTaskListeners(List.of(listener));

        addExtensionElements(task, id, "human_review", label, config);
        return task;
    }

    /**
     * 同步执行节点 → ServiceTask (delegateExpression)
     */
    private ServiceTask createServiceTask(String id, String label, String nodeType, Map<String, Object> config) {
        ServiceTask task = new ServiceTask();
        task.setId(id);
        task.setName(label);
        task.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        task.setImplementation(SERVICE_TASK_DELEGATE);

        addExtensionElements(task, id, nodeType, label, config);
        return task;
    }

    private ExclusiveGateway createExclusiveGateway(String id, String label) {
        ExclusiveGateway gateway = new ExclusiveGateway();
        gateway.setId(id);
        gateway.setName(label);
        return gateway;
    }

    private ParallelGateway createParallelGateway(String id, String label) {
        ParallelGateway gateway = new ParallelGateway();
        gateway.setId(id);
        gateway.setName(label);
        return gateway;
    }

    private EndEvent createEndEvent(String id, String label) {
        EndEvent event = new EndEvent();
        event.setId(id);
        event.setName(label);

        // 添加 end ExecutionListener 触发工作流完成逻辑
        FlowableListener listener = new FlowableListener();
        listener.setEvent("end");
        listener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        listener.setImplementation("${" + END_EVENT_LISTENER + "}");
        event.setExecutionListeners(List.of(listener));

        return event;
    }

    // ===== 边转换 =====

    private SequenceFlow convertEdge(Map<String, Object> edge, List<Map<String, Object>> nodes) {
        String edgeId = str(edge, "id");
        String source = str(edge, "source");
        String target = str(edge, "target");

        if (!StringUtils.hasText(source) || !StringUtils.hasText(target)) {
            return null;
        }

        SequenceFlow flow = new SequenceFlow();
        flow.setId(StringUtils.hasText(edgeId) ? edgeId : "edge_" + source + "_" + target);
        flow.setSourceRef(source);
        flow.setTargetRef(target);

        // 如果源节点是 condition (ExclusiveGateway)，从 edge data 中提取条件表达式
        Map<String, Object> sourceNode = findNode(source, nodes);
        if (sourceNode != null && "condition".equals(str(sourceNode, "type"))) {
            Map<String, Object> edgeData = getEdgeData(edge);
            String condition = str(edgeData, "condition");
            if (StringUtils.hasText(condition)) {
                // condition 值为 "true"/"false"，映射为 UEL 表达式
                flow.setConditionExpression("${conditionResult == '" + condition + "'}");
            }
        }

        return flow;
    }

    // ===== 工具方法 =====

    /**
     * 将节点的自定义属性存入 BPMN extensionElements，
     * 这样 Delegate/Listener 可以在运行时读取原始 nodeId、nodeType、config 等信息。
     */
    private void addExtensionElements(FlowElement element, String nodeId, String nodeType,
                                       String nodeLabel, Map<String, Object> config) {
        addExtensionElement(element, "nodeId", nodeId);
        addExtensionElement(element, "nodeType", nodeType);
        addExtensionElement(element, "nodeLabel", nodeLabel != null ? nodeLabel : "");
        if (config != null && !config.isEmpty()) {
            try {
                addExtensionElement(element, "nodeConfig", objectMapper.writeValueAsString(config));
            } catch (Exception e) {
                log.warn("序列化节点配置失败: nodeId={}", nodeId, e);
            }
        }
    }

    private void addExtensionElement(FlowElement flowElement, String name, String value) {
        ExtensionElement ext = new ExtensionElement();
        ext.setNamespace(NAMESPACE);
        ext.setNamespacePrefix("sf");
        ext.setName(name);
        ext.setElementText(value);
        flowElement.addExtensionElement(ext);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getConfig(Map<String, Object> node) {
        Object config = node.get("config");
        return config instanceof Map ? (Map<String, Object>) config : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getEdgeData(Map<String, Object> edge) {
        Object data = edge.get("data");
        return data instanceof Map ? (Map<String, Object>) data : Map.of();
    }

    private Map<String, Object> findNode(String nodeId, List<Map<String, Object>> nodes) {
        return nodes.stream()
                .filter(n -> nodeId.equals(str(n, "id")))
                .findFirst()
                .orElse(null);
    }

    private String str(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : null;
    }
}
