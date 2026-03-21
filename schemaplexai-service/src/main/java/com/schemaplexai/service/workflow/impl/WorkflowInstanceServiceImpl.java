package com.schemaplexai.service.workflow.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.converter.WorkflowInstanceConverter;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceQueryRequest;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.model.vo.workflow.WorkflowNodeExecutionVO;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import com.schemaplexai.service.workflow.engine.WorkflowNodeEngine;
import com.schemaplexai.service.workflow.validator.WorkflowValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowInstanceVO create(WorkflowInstanceCreateRequest request) {
        // 查模板并快照 definition
        var template = templateMapper.selectById(request.getTemplateId());
        if (template == null) {
            throw new BusinessException(ResultCode.WORKFLOW_NOT_FOUND);
        }

        var instance = instanceConverter.fromCreateRequest(request);
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
        workflowValidator.validateCanStart(instance);

        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        instance.setStartedAt(LocalDateTime.now());
        instance.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);

        // 初始化节点执行记录并驱动第一个节点
        workflowNodeEngine.initAndDriveWorkflow(instance);

        log.info("启动工作流实例: instanceId={}", id);
        return enrichWithNodeExecutions(instance);
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

        // TODO: 对接 Flowable — runtimeService.suspendProcessInstanceById

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

        // TODO: 对接 Flowable — runtimeService.activateProcessInstanceById

        log.info("恢复工作流实例: instanceId={}", id);
        return enrichWithNodeExecutions(instance);
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

        // TODO: 对接 Flowable — runtimeService.deleteProcessInstance

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
        wrapper.orderByDesc(WorkflowInstance::getCreatedAt);

        var result = instanceMapper.selectPage(page, wrapper);
        var voList = result.getRecords().stream()
                .map(this::enrichWithNodeExecutions)
                .collect(Collectors.toList());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public List<WorkflowNodeExecutionVO> getNodeExecutions(String instanceId) {
        requireExists(instanceId);
        var executions = nodeExecutionMapper.selectByInstanceId(instanceId);
        return executions.stream().map(this::toNodeExecutionVO).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveNode(String instanceId, String nodeId, String comment) {
        var instance = requireExists(instanceId);
        // 校验实例状态必须为运行中
        if (!WorkflowInstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }
        var execution = findNodeExecution(instanceId, nodeId);
        // 校验节点状态必须为待处理或运行中
        if (!"pending".equals(execution.getStatus()) && !"running".equals(execution.getStatus())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }

        execution.setStatus("completed");
        execution.setCompletedAt(LocalDateTime.now());
        Map<String, Object> outputData = new HashMap<>();
        outputData.put("approvalResult", "approved");
        outputData.put("comment", comment);
        execution.setOutputData(outputData);
        nodeExecutionMapper.updateById(execution);

        // 审批通过后，推进工作流到下一节点
        workflowNodeEngine.advanceWorkflow(instanceId, nodeId, outputData);

        log.info("审批通过: instanceId={}, nodeId={}", instanceId, nodeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectNode(String instanceId, String nodeId, String comment, String rollbackToNodeId) {
        var instance = requireExists(instanceId);
        // 校验实例状态必须为运行中
        if (!WorkflowInstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }
        var execution = findNodeExecution(instanceId, nodeId);
        // 校验节点状态必须为待处理或运行中
        if (!"pending".equals(execution.getStatus()) && !"running".equals(execution.getStatus())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }

        execution.setStatus("failed");
        execution.setCompletedAt(LocalDateTime.now());
        Map<String, Object> outputData = new HashMap<>();
        outputData.put("approvalResult", "rejected");
        outputData.put("comment", comment);
        outputData.put("rollbackToNodeId", rollbackToNodeId);
        execution.setOutputData(outputData);
        nodeExecutionMapper.updateById(execution);

        // TODO: 对接 Flowable — 终止当前 Task，重新启动目标节点

        log.info("审批拒绝: instanceId={}, nodeId={}, rollbackTo={}",
                instanceId, nodeId, rollbackToNodeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requestModify(String instanceId, String nodeId, String modifyInstruction) {
        requireExists(instanceId);
        var execution = findNodeExecution(instanceId, nodeId);

        Map<String, Object> outputData = execution.getOutputData() != null
                ? new HashMap<>(execution.getOutputData()) : new HashMap<>();
        outputData.put("modifyInstruction", modifyInstruction);
        execution.setOutputData(outputData);
        nodeExecutionMapper.updateById(execution);

        // TODO: 对接 Flowable — 发送修改请求通知

        log.info("请求修改: instanceId={}, nodeId={}", instanceId, nodeId);
    }

    // ===== 私有方法 =====

    private WorkflowInstance requireExists(String id) {
        var instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException(ResultCode.WORKFLOW_INSTANCE_NOT_FOUND);
        }
        return instance;
    }

    private WorkflowNodeExecution findNodeExecution(String instanceId, String nodeId) {
        var executions = nodeExecutionMapper.selectByInstanceId(instanceId);
        return executions.stream()
                .filter(e -> nodeId.equals(e.getNodeId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.WORKFLOW_NODE_NOT_FOUND));
    }

    private WorkflowInstanceVO enrichWithNodeExecutions(WorkflowInstance instance) {
        var vo = instanceConverter.toVO(instance);
        var executions = nodeExecutionMapper.selectByInstanceId(instance.getId());
        vo.setNodeExecutions(executions.stream().map(this::toNodeExecutionVO).collect(Collectors.toList()));
        return vo;
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
        vo.setStartedAt(execution.getStartedAt());
        vo.setCompletedAt(execution.getCompletedAt());
        return vo;
    }
}
