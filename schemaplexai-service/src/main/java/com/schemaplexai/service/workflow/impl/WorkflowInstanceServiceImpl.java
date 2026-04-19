package com.schemaplexai.service.workflow.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.converter.WorkflowInstanceConverter;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceQueryRequest;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.model.vo.workflow.WorkflowNodeExecutionVO;
import com.schemaplexai.model.vo.workflow.WorkflowTemplateNodeExecutionVO;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import com.schemaplexai.service.workflow.engine.WorkflowNodeEngine;
import com.schemaplexai.service.workflow.flowable.FlowableWorkflowBridge;
import com.schemaplexai.service.workflow.validator.WorkflowValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工作流实例服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowInstanceServiceImpl implements WorkflowInstanceService {

    private final WorkflowInstanceMapper instanceMapper;
    private final WorkflowTemplateMapper templateMapper;
    private final WorkflowNodeExecutionMapper nodeExecutionMapper;
    private final WorkflowInstanceConverter instanceConverter;
    private final WorkflowValidator workflowValidator;
    private final WorkflowNodeEngine workflowNodeEngine;
    private final FlowableWorkflowBridge flowableBridge;
    private final ObjectProvider<SecurityRuntimeGuardService> securityRuntimeGuardServiceProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowInstanceVO create(WorkflowInstanceCreateRequest request) {
        // 查模板并快照 definition
        var template = templateMapper.selectById(request.getTemplateId());
        if (template == null) {
            throw new BusinessException(ResultCode.WORKFLOW_NOT_FOUND);
        }

        var instance = instanceConverter.fromCreateRequest(request);
        instance.setTenantId(resolveTenantId(request));
        instance.setDefinition(template.getDefinition());
        instanceMapper.insert(instance);

        log.info("创建工作流实例成功: instanceId={}, templateId={}, name={}",
                instance.getId(), request.getTemplateId(), request.getName());
        return enrichWithNodeExecutions(instance);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowInstanceVO start(String id) {
        var instance = requireExists(id);
        if (isAwaitingOriginalRequirement(instance)) {
            log.info("工作流实例缺少原始需求，保持待启动状态: instanceId={}", id);
            return enrichWithNodeExecutions(instance);
        }
        workflowValidator.validateCanStart(instance);

        SecurityCheckDecisionVO securityDecision = securityRuntimeGuardServiceProvider.getObject().evaluate(
                buildStartSecurityCheckRequest(instance),
                null
        );
        Map<String, Object> variables = appendSecurityDecision(instance.getVariables(), securityDecision);
        if (securityDecision != null && SecurityComplianceConstant.DECISION_BLOCK.equals(securityDecision.getDecision())) {
            instance.setVariables(variables);
            instance.setUpdatedAt(LocalDateTime.now());
            instanceMapper.updateById(instance);
            return enrichWithNodeExecutions(instance);
        }
        if (securityDecision != null && SecurityComplianceConstant.DECISION_PAUSE.equals(securityDecision.getDecision())) {
            instance.setStatus(WorkflowInstanceStatusEnum.PAUSED.getCode());
            instance.setVariables(variables);
            instance.setUpdatedAt(LocalDateTime.now());
            instanceMapper.updateById(instance);
            return enrichWithNodeExecutions(instance);
        }

        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        instance.setStartedAt(LocalDateTime.now());
        instance.setVariables(variables);
        instance.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);

        // 初始化节点执行记录
        workflowNodeEngine.initNodeExecutionRecords(instance);

        // 通过 Flowable 启动流程实例
        var template = templateMapper.selectById(instance.getTemplateId());
        if (template != null && StringUtils.hasText(template.getProcessDefinitionId())) {
            Map<String, Object> processVariables = toFlowableVariables(variables);
            processVariables.put("sfInstanceId", instance.getId());
            String processInstanceId = flowableBridge.startProcess(
                    template.getProcessDefinitionId(), instance.getId(), processVariables);
            // Flowable 可能在 startProcess 返回前已同步执行到结束节点，这里只回填流程实例 ID，
            // 避免用启动前的旧快照覆盖 completed/currentNodeId 等最新状态。
            WorkflowInstance processUpdate = new WorkflowInstance();
            processUpdate.setId(instance.getId());
            processUpdate.setProcessInstanceId(processInstanceId);
            processUpdate.setUpdatedAt(LocalDateTime.now());
            instanceMapper.updateById(processUpdate);
            log.info("通过 Flowable 启动工作流: instanceId={}, processInstanceId={}", id, processInstanceId);
        } else {
            // 降级：模板未部署到 Flowable，使用原有引擎驱动
            log.warn("模板未部署到 Flowable，使用原有引擎: instanceId={}, templateId={}",
                    id, instance.getTemplateId());
            workflowNodeEngine.initAndDriveWorkflow(instance);
        }

        log.info("启动工作流实例: instanceId={}", id);
        return enrichWithNodeExecutions(requireExists(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowInstanceVO pause(String id) {
        var instance = requireExists(id);
        workflowValidator.validateStatusTransition(instance.getStatus(),
                WorkflowInstanceStatusEnum.PAUSED.getCode());

        instance.setStatus(WorkflowInstanceStatusEnum.PAUSED.getCode());
        instance.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);

        // Flowable 流程实例同步暂停
        flowableBridge.suspendProcess(instance.getProcessInstanceId());

        log.info("暂停工作流实例: instanceId={}", id);
        return enrichWithNodeExecutions(instance);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowInstanceVO resume(String id) {
        var instance = requireExists(id);
        workflowValidator.validateStatusTransition(instance.getStatus(),
                WorkflowInstanceStatusEnum.RUNNING.getCode());

        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        instance.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);

        // Flowable 流程实例同步恢复
        flowableBridge.activateProcess(instance.getProcessInstanceId());

        boolean pendingStart = instance.getVariables() != null
                && Boolean.TRUE.equals(instance.getVariables().get("securityPendingStart"));
        long nodeCount = nodeExecutionMapper.selectCount(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .eq(WorkflowNodeExecution::getInstanceId, instance.getId())
        );
        if (pendingStart || nodeCount == 0) {
            Map<String, Object> variables = new HashMap<>(instance.getVariables() == null ? Map.of() : instance.getVariables());
            variables.remove("securityPendingStart");
            instance.setVariables(variables);
            instanceMapper.updateById(instance);

            // 需要重新通过 Flowable 启动
            var template = templateMapper.selectById(instance.getTemplateId());
            if (template != null && StringUtils.hasText(template.getProcessDefinitionId())) {
                workflowNodeEngine.initNodeExecutionRecords(instance);
                Map<String, Object> processVariables = toFlowableVariables(variables);
                processVariables.put("sfInstanceId", instance.getId());
                String processInstanceId = flowableBridge.startProcess(
                        template.getProcessDefinitionId(), instance.getId(), processVariables);
                WorkflowInstance processUpdate = new WorkflowInstance();
                processUpdate.setId(instance.getId());
                processUpdate.setProcessInstanceId(processInstanceId);
                processUpdate.setUpdatedAt(LocalDateTime.now());
                instanceMapper.updateById(processUpdate);
            } else {
                workflowNodeEngine.initAndDriveWorkflow(instance);
            }
        }

        log.info("恢复工作流实例: instanceId={}", id);
        return enrichWithNodeExecutions(requireExists(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void terminate(String id) {
        var instance = requireExists(id);
        var status = instance.getStatus();
        if (!WorkflowInstanceStatusEnum.RUNNING.getCode().equals(status)
                && !WorkflowInstanceStatusEnum.PAUSED.getCode().equals(status)) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }

        instance.setStatus(WorkflowInstanceStatusEnum.CANCELLED.getCode());
        instance.setCompletedAt(LocalDateTime.now());
        instance.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);

        // Flowable 流程实例同步删除
        flowableBridge.deleteProcess(instance.getProcessInstanceId(), "用户终止");

        log.info("终止工作流实例: instanceId={}", id);
    }

    @Override
    public WorkflowInstanceVO getById(String id) {
        var instance = requireExists(id);
        return enrichWithNodeExecutions(instance);
    }

    @Override
    public PageResult<WorkflowInstanceVO> page(WorkflowInstanceQueryRequest query) {
        var page = new Page<WorkflowInstance>(query.getPage(), query.getSize());
        var wrapper = new LambdaQueryWrapper<WorkflowInstance>();

        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(WorkflowInstance::getStatus, query.getStatus());
        }
        if (StringUtils.hasText(query.getSpecId())) {
            wrapper.eq(WorkflowInstance::getSpecId, query.getSpecId());
        }
        if (StringUtils.hasText(query.getTriggerType())) {
            wrapper.apply("variables->>'triggerType' = {0}", query.getTriggerType());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(WorkflowInstance::getName, query.getKeyword());
        }
        wrapper.orderByDesc(WorkflowInstance::getCreatedAt);

        var result = instanceMapper.selectPage(page, wrapper);
        var voList = result.getRecords().stream()
                .map(this::toListVO)
                .toList();
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public List<WorkflowNodeExecutionVO> getNodeExecutions(String instanceId) {
        requireExists(instanceId);
        var executions = listNodeExecutions(instanceId);
        return executions.stream().map(this::toNodeExecutionVO).toList();
    }

    @Override
    public List<WorkflowTemplateNodeExecutionVO> listTemplateNodeExecutions(String templateId, String nodeId, Integer size) {
        if (!StringUtils.hasText(templateId) || !StringUtils.hasText(nodeId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "模板ID和节点ID不能为空");
        }
        int limit = size == null || size <= 0 ? 10 : Math.min(size, 50);

        var instancePage = new Page<WorkflowInstance>(1, limit);
        var instanceResult = instanceMapper.selectPage(instancePage, new LambdaQueryWrapper<WorkflowInstance>()
                .eq(WorkflowInstance::getTemplateId, templateId)
                .orderByDesc(WorkflowInstance::getCreatedAt));
        if (instanceResult.getRecords().isEmpty()) {
            return List.of();
        }

        Map<String, WorkflowInstance> instanceMap = new HashMap<>();
        List<String> instanceIds = new ArrayList<>();
        for (WorkflowInstance instance : instanceResult.getRecords()) {
            instanceMap.put(instance.getId(), instance);
            instanceIds.add(instance.getId());
        }

        return nodeExecutionMapper.selectList(new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .in(WorkflowNodeExecution::getInstanceId, instanceIds)
                        .eq(WorkflowNodeExecution::getNodeId, nodeId)
                        .orderByDesc(WorkflowNodeExecution::getCreatedAt))
                .stream()
                .map(execution -> toTemplateNodeExecutionVO(execution, instanceMap.get(execution.getInstanceId())))
                .filter(Objects::nonNull)
                .limit(limit)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveNode(String instanceId, String nodeId, String comment) {
        var instance = requireExists(instanceId);
        if (!WorkflowInstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())
                && !WorkflowInstanceStatusEnum.PAUSED.getCode().equals(instance.getStatus())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }
        var execution = findNodeExecution(instanceId, nodeId);
        if (!WorkflowInstanceStatusEnum.PENDING.getCode().equals(execution.getStatus())
                && !WorkflowInstanceStatusEnum.RUNNING.getCode().equals(execution.getStatus())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }

        if (WorkflowInstanceStatusEnum.PAUSED.getCode().equals(instance.getStatus())) {
            instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
            instance.setUpdatedAt(LocalDateTime.now());
            instanceMapper.updateById(instance);
        }

        execution.setStatus(WorkflowInstanceStatusEnum.COMPLETED.getCode());
        execution.setCompletedAt(LocalDateTime.now());
        Map<String, Object> outputData = new HashMap<>();
        outputData.put("approvalResult", CommonConstant.APPROVAL_RESULT_APPROVED);
        outputData.put("comment", comment);
        execution.setOutputData(outputData);
        nodeExecutionMapper.updateById(execution);

        // 审批通过后，通过 Flowable 完成 UserTask 自动流转到下一节点
        if (StringUtils.hasText(instance.getProcessInstanceId())) {
            flowableBridge.completeUserTask(instance.getProcessInstanceId(), nodeId, outputData);
        } else {
            // 降级：无 Flowable 流程实例，使用原有引擎推进
            workflowNodeEngine.advanceWorkflow(instanceId, nodeId, outputData);
        }

        log.info("审批通过: instanceId={}, nodeId={}", instanceId, nodeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectNode(String instanceId, String nodeId, String comment, String rollbackToNodeId) {
        var instance = requireExists(instanceId);
        if (!WorkflowInstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())
                && !WorkflowInstanceStatusEnum.PAUSED.getCode().equals(instance.getStatus())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }
        var execution = findNodeExecution(instanceId, nodeId);
        if (!WorkflowInstanceStatusEnum.PENDING.getCode().equals(execution.getStatus())
                && !WorkflowInstanceStatusEnum.RUNNING.getCode().equals(execution.getStatus())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }

        execution.setStatus(WorkflowInstanceStatusEnum.FAILED.getCode());
        execution.setCompletedAt(LocalDateTime.now());
        Map<String, Object> outputData = new HashMap<>();
        outputData.put("approvalResult", CommonConstant.APPROVAL_RESULT_REJECTED);
        outputData.put("comment", comment);
        String resolvedRollbackNodeId = resolveRollbackNodeId(instance, nodeId, rollbackToNodeId);
        outputData.put("rollbackToNodeId", resolvedRollbackNodeId);
        execution.setOutputData(outputData);
        nodeExecutionMapper.updateById(execution);

        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        instance.setCurrentNodeId(resolvedRollbackNodeId);
        instance.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);

        // Flowable 层面回滚活动状态
        if (StringUtils.hasText(instance.getProcessInstanceId())) {
            flowableBridge.moveActivityState(instance.getProcessInstanceId(), nodeId, resolvedRollbackNodeId);
        }
        workflowNodeEngine.rollbackToNode(instanceId, resolvedRollbackNodeId, outputData);

        log.info("审批拒绝: instanceId={}, nodeId={}, rollbackTo={}",
                instanceId, nodeId, resolvedRollbackNodeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requestModify(String instanceId, String nodeId, String modifyInstruction) {
        var instance = requireExists(instanceId);
        var execution = findNodeExecution(instanceId, nodeId);
        if (!WorkflowInstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())
                && !WorkflowInstanceStatusEnum.PAUSED.getCode().equals(instance.getStatus())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }
        String rollbackToNodeId = resolveRollbackNodeId(instance, nodeId, null);

        Map<String, Object> outputData = execution.getOutputData() != null
                ? new HashMap<>(execution.getOutputData()) : new HashMap<>();
        outputData.put("approvalResult", "request_modify");
        outputData.put("modifyInstruction", modifyInstruction);
        outputData.put("rollbackToNodeId", rollbackToNodeId);
        execution.setStatus(WorkflowInstanceStatusEnum.FAILED.getCode());
        execution.setCompletedAt(LocalDateTime.now());
        execution.setOutputData(outputData);
        nodeExecutionMapper.updateById(execution);

        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        instance.setCurrentNodeId(rollbackToNodeId);
        instance.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);

        // Flowable 层面回滚活动状态
        if (StringUtils.hasText(instance.getProcessInstanceId())) {
            flowableBridge.moveActivityState(instance.getProcessInstanceId(), nodeId, rollbackToNodeId);
        }
        workflowNodeEngine.rollbackToNode(instanceId, rollbackToNodeId, outputData);

        log.info("请求修改: instanceId={}, nodeId={}, rollbackTo={}", instanceId, nodeId, rollbackToNodeId);
    }

    // ===== 私有方法 =====

    @Override
    public WorkflowInstance requireEntity(String id) {
        return requireExists(id);
    }

    private WorkflowInstance requireExists(String id) {
        var instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException(ResultCode.WORKFLOW_INSTANCE_NOT_FOUND);
        }
        return instance;
    }

    private WorkflowNodeExecution findNodeExecution(String instanceId, String nodeId) {
        var executions = listNodeExecutions(instanceId);
        return executions.stream()
                .filter(e -> nodeId.equals(e.getNodeId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.WORKFLOW_NODE_NOT_FOUND));
    }

    private String resolveTenantId(WorkflowInstanceCreateRequest request) {
        if (request != null && StringUtils.hasText(request.getTenantId())) {
            return request.getTenantId().trim();
        }
        String securityTenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(securityTenantId)) {
            return securityTenantId.trim();
        }
        throw new BusinessException(ResultCode.FAIL, "工作流实例缺少租户信息");
    }

    private SecurityRuntimeCheckRequest buildStartSecurityCheckRequest(WorkflowInstance instance) {
        var request = new SecurityRuntimeCheckRequest();
        request.setTenantId(instance.getTenantId());
        request.setScene(SecurityComplianceConstant.CHECK_SCENE_WORKFLOW_START);
        request.setDomainCode(SecurityComplianceConstant.DOMAIN_RUNTIME);
        request.setResourceType(SecurityComplianceConstant.RESOURCE_TYPE_WORKFLOW_INSTANCE);
        request.setResourceId(instance.getId());
        request.setResourceName(instance.getName());
        request.setWorkflowInstanceId(instance.getId());
        request.setContent(instance.getName());
        request.setContext(instance.getVariables());
        return request;
    }

    private Map<String, Object> appendSecurityDecision(Map<String, Object> original, SecurityCheckDecisionVO decision) {
        Map<String, Object> variables = new HashMap<>(original == null ? Map.of() : original);
        if (decision == null) {
            return variables;
        }
        variables.put("securityDecision", decision);
        if (SecurityComplianceConstant.DECISION_PAUSE.equals(decision.getDecision())) {
            variables.put("securityPendingStart", true);
        }
        return variables;
    }

    /**
     * 列表查询用 VO 转换（不加载节点执行记录，提升性能）
     */
    private WorkflowInstanceVO toListVO(WorkflowInstance instance) {
        var vo = instanceConverter.toVO(instance);
        populateExtraFields(vo, instance);
        return vo;
    }

    private WorkflowInstanceVO enrichWithNodeExecutions(WorkflowInstance instance) {
        var vo = instanceConverter.toVO(instance);
        populateExtraFields(vo, instance);
        var executions = listNodeExecutions(instance.getId());
        vo.setNodeExecutions(executions.stream().map(this::toNodeExecutionVO).toList());
        return vo;
    }

    /**
     * 补充 VO 中需要从 variables/关联表提取的额外字段
     */
    private void populateExtraFields(WorkflowInstanceVO vo, WorkflowInstance instance) {
        // 触发类型
        if (instance.getVariables() != null) {
            var triggerType = instance.getVariables().get("triggerType");
            vo.setTriggerType(triggerType != null ? String.valueOf(triggerType) : null);
        }
        // 错误信息
        if (instance.getVariables() != null) {
            var errorMsg = instance.getVariables().get("errorMessage");
            vo.setErrorMessage(errorMsg != null ? String.valueOf(errorMsg) : null);
        }
        // 模板名称
        if (StringUtils.hasText(instance.getTemplateId())) {
            var template = templateMapper.selectById(instance.getTemplateId());
            if (template != null) {
                vo.setTemplateName(template.getName());
            }
        }
    }

    private boolean isAwaitingOriginalRequirement(WorkflowInstance instance) {
        if (instance == null || instance.getVariables() == null) {
            return false;
        }
        Object triggerType = instance.getVariables().get("triggerType");
        if (!"spec-workbench".equals(triggerType == null ? null : String.valueOf(triggerType))) {
            return false;
        }
        String originalRequirement = readString(instance.getVariables(), "originalRequirement");
        String specDescription = readString(instance.getVariables(), "specDescription");
        return !StringUtils.hasText(originalRequirement) && !StringUtils.hasText(specDescription);
    }

    private List<WorkflowNodeExecution> listNodeExecutions(String instanceId) {
        return nodeExecutionMapper.selectList(new LambdaQueryWrapper<WorkflowNodeExecution>()
                .eq(WorkflowNodeExecution::getInstanceId, instanceId)
                .orderByAsc(WorkflowNodeExecution::getCreatedAt));
    }

    private WorkflowNodeExecutionVO toNodeExecutionVO(WorkflowNodeExecution execution) {
        var vo = new WorkflowNodeExecutionVO();
        vo.setId(execution.getId());
        vo.setNodeId(execution.getNodeId());
        vo.setNodeType(execution.getNodeType());
        vo.setNodeLabel(execution.getNodeLabel());
        vo.setStatus(execution.getStatus());
        vo.setInputData(execution.getInputData());
        vo.setOutputData(execution.getOutputData());
        vo.setErrorMessage(execution.getErrorMessage());
        vo.setReviewSessionId(execution.getReviewSessionId());
        vo.setActionUrl(execution.getActionUrl());
        vo.setStartedAt(execution.getStartedAt());
        vo.setCompletedAt(execution.getCompletedAt());
        return vo;
    }

    private WorkflowTemplateNodeExecutionVO toTemplateNodeExecutionVO(WorkflowNodeExecution execution, WorkflowInstance instance) {
        if (execution == null || instance == null) {
            return null;
        }
        var vo = new WorkflowTemplateNodeExecutionVO();
        vo.setId(execution.getId());
        vo.setInstanceId(instance.getId());
        vo.setInstanceName(instance.getName());
        vo.setSpecId(instance.getSpecId());
        vo.setNodeId(execution.getNodeId());
        vo.setNodeType(execution.getNodeType());
        vo.setNodeLabel(execution.getNodeLabel());
        vo.setStatus(execution.getStatus());
        vo.setInputData(execution.getInputData());
        vo.setOutputData(execution.getOutputData());
        vo.setErrorMessage(execution.getErrorMessage());
        vo.setStartedAt(execution.getStartedAt());
        vo.setCompletedAt(execution.getCompletedAt());
        vo.setCreatedAt(execution.getCreatedAt());
        return vo;
    }

    private String readString(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 将工作流变量过滤为 Flowable 可序列化的类型（只保留基本类型和 String）
     */
    private Map<String, Object> toFlowableVariables(Map<String, Object> variables) {
        Map<String, Object> safe = new HashMap<>();
        if (variables == null) {
            return safe;
        }
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            Object value = entry.getValue();
            if (value == null || value instanceof String || value instanceof Number
                    || value instanceof Boolean) {
                safe.put(entry.getKey(), value);
            }
            // 复杂对象（VO、Map、List）不传给 Flowable，仅保留在 sf_workflow_instance.variables
        }
        return safe;
    }

    @SuppressWarnings("unchecked")
    private String resolveRollbackNodeId(WorkflowInstance instance, String currentNodeId, String preferredNodeId) {
        if (StringUtils.hasText(preferredNodeId)) {
            return preferredNodeId.trim();
        }
        if (instance == null || instance.getDefinition() == null) {
            throw new BusinessException(ResultCode.WORKFLOW_NODE_NOT_FOUND);
        }
        Object rawEdges = instance.getDefinition().get("edges");
        if (!(rawEdges instanceof List<?> edges)) {
            throw new BusinessException(ResultCode.WORKFLOW_NODE_NOT_FOUND);
        }
        return edges.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .filter(edge -> currentNodeId.equals(String.valueOf(edge.get("target"))))
                .map(edge -> edge.get("source"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.WORKFLOW_NODE_NOT_FOUND, "未找到回滚节点"));
    }
}
