package com.schemaplexai.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentActionMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityIncidentActionRequest;
import com.schemaplexai.model.dto.security.SecurityIncidentQueryRequest;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.SecurityIncident;
import com.schemaplexai.model.entity.SecurityIncidentAction;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.model.vo.security.SecurityIncidentActionVO;
import com.schemaplexai.model.vo.security.SecurityIncidentVO;
import com.schemaplexai.service.agent.runtime.AgentRuntimeOrchestrator;
import com.schemaplexai.service.security.SecurityAuditEventService;
import com.schemaplexai.service.security.SecurityIncidentService;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 安全事件服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityIncidentServiceImpl implements SecurityIncidentService {

    private static final int RECENT_LIMIT = 20;

    private final SecurityIncidentMapper securityIncidentMapper;
    private final SecurityIncidentActionMapper securityIncidentActionMapper;
    private final SecurityAuditEventService securityAuditEventService;
    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentMapper agentMapper;
    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final ObjectProvider<AgentRuntimeOrchestrator> agentRuntimeOrchestratorProvider;
    private final WorkflowInstanceService workflowInstanceService;

    @Override
    public PageResult<SecurityIncidentVO> page(SecurityIncidentQueryRequest request) {
        var page = new Page<SecurityIncident>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<SecurityIncident>();
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(SecurityIncident::getTenantId, tenantId);
        }
        if (StringUtils.hasText(request.getDomainCode())) {
            wrapper.eq(SecurityIncident::getDomainCode, request.getDomainCode());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(SecurityIncident::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getRiskLevel())) {
            wrapper.eq(SecurityIncident::getRiskLevel, request.getRiskLevel());
        }
        if (StringUtils.hasText(request.getSourceType())) {
            wrapper.eq(SecurityIncident::getSourceType, request.getSourceType());
        }
        if (StringUtils.hasText(request.getDecision())) {
            wrapper.eq(SecurityIncident::getDecision, request.getDecision());
        }
        if (StringUtils.hasText(request.getTraceId())) {
            wrapper.eq(SecurityIncident::getTraceId, request.getTraceId());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(q -> q.like(SecurityIncident::getIncidentNo, request.getKeyword())
                    .or().like(SecurityIncident::getEventTitle, request.getKeyword())
                    .or().like(SecurityIncident::getEventDetail, request.getKeyword())
                    .or().like(SecurityIncident::getPolicyCode, request.getKeyword())
                    .or().like(SecurityIncident::getSourceName, request.getKeyword()));
        }
        if (request.getStartTime() != null) {
            wrapper.ge(SecurityIncident::getCreatedAt, request.getStartTime());
        }
        if (request.getEndTime() != null) {
            wrapper.le(SecurityIncident::getCreatedAt, request.getEndTime());
        }
        wrapper.orderByDesc(SecurityIncident::getCreatedAt);
        var result = securityIncidentMapper.selectPage(page, wrapper);
        return new PageResult<>(result.getRecords().stream().map(this::toVO).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public SecurityIncidentVO getById(String id) {
        return toVO(requireIncident(id), true);
    }

    @Override
    public List<SecurityIncidentVO> listRecent(int limit) {
        int finalLimit = Math.min(Math.max(limit, 1), RECENT_LIMIT);
        String tenantId = SecurityUtil.getCurrentTenantId();
        return securityIncidentMapper.selectList(
                new LambdaQueryWrapper<SecurityIncident>()
                        .eq(StringUtils.hasText(tenantId), SecurityIncident::getTenantId, tenantId)
                        .orderByDesc(SecurityIncident::getCreatedAt)
                        .last("LIMIT " + finalLimit)
        ).stream().map(this::toVO).toList();
    }

    @Override
    public List<SecurityIncidentActionVO> listActions(String incidentId) {
        requireIncident(incidentId);
        return securityIncidentActionMapper.selectList(
                new LambdaQueryWrapper<SecurityIncidentAction>()
                        .eq(SecurityIncidentAction::getIncidentId, incidentId)
                        .orderByAsc(SecurityIncidentAction::getActionAt)
        ).stream().map(this::toActionVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecurityIncidentVO assign(String id, SecurityIncidentActionRequest request, SecurityAuditContext auditContext) {
        var incident = requireIncident(id);
        incident.setStatus(SecurityComplianceConstant.INCIDENT_STATUS_ASSIGNED);
        incident.setAssignedTo(request.getAssigneeId());
        incident.setAssignedName(request.getAssigneeName());
        securityIncidentMapper.updateById(incident);
        appendAction(incident, SecurityComplianceConstant.ACTION_ASSIGN, "success", request.getComment(), auditContext,
                Map.of("assigneeId", safeValue(request.getAssigneeId()), "assigneeName", safeValue(request.getAssigneeName())));
        return toVO(incident, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecurityIncidentVO resolve(String id, SecurityIncidentActionRequest request, SecurityAuditContext auditContext) {
        var incident = requireIncident(id);
        incident.setStatus(SecurityComplianceConstant.INCIDENT_STATUS_RESOLVED);
        incident.setResolvedAt(LocalDateTime.now());
        incident.setResolutionSummary(request.getComment());
        securityIncidentMapper.updateById(incident);
        appendAction(incident, SecurityComplianceConstant.ACTION_RESOLVE, "success", request.getComment(), auditContext, Map.of());
        return toVO(incident, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecurityIncidentVO ignore(String id, SecurityIncidentActionRequest request, SecurityAuditContext auditContext) {
        var incident = requireIncident(id);
        incident.setStatus(SecurityComplianceConstant.INCIDENT_STATUS_IGNORED);
        securityIncidentMapper.updateById(incident);
        appendAction(incident, SecurityComplianceConstant.ACTION_IGNORE, "success", request.getComment(), auditContext, Map.of());
        return toVO(incident, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecurityIncidentVO escalate(String id, SecurityIncidentActionRequest request, SecurityAuditContext auditContext) {
        var incident = requireIncident(id);
        incident.setStatus(SecurityComplianceConstant.INCIDENT_STATUS_ESCALATED);
        securityIncidentMapper.updateById(incident);
        appendAction(incident, SecurityComplianceConstant.ACTION_ESCALATE, "success", request.getComment(), auditContext, Map.of());
        return toVO(incident, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecurityIncidentVO resumeResource(String id, SecurityAuditContext auditContext) {
        var incident = requireIncident(id);
        if (SecurityComplianceConstant.RESOURCE_TYPE_AGENT_EXECUTION.equals(incident.getSourceType())) {
            resumeAgentExecution(incident);
        } else if (SecurityComplianceConstant.RESOURCE_TYPE_WORKFLOW_INSTANCE.equals(incident.getSourceType())) {
            workflowInstanceService.resume(incident.getSourceId());
        } else {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前事件不支持恢复资源");
        }
        incident.setStatus(SecurityComplianceConstant.INCIDENT_STATUS_INVESTIGATING);
        securityIncidentMapper.updateById(incident);
        appendAction(incident, SecurityComplianceConstant.ACTION_RESUME_RESOURCE, "success", "已恢复关联业务流程", auditContext, Map.of());
        return toVO(incident, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createFromDecision(SecurityCheckDecisionVO decision,
                                     String tenantId,
                                     String domainCode,
                                     String sourceType,
                                     String sourceId,
                                     String sourceName) {
        if (decision == null || !Boolean.TRUE.equals(decision.getShouldCreateIncident())) {
            return null;
        }
        var incident = new SecurityIncident();
        incident.setTenantId(tenantId);
        incident.setIncidentNo(buildIncidentNo());
        incident.setTraceId(decision.getTraceId());
        incident.setDomainCode(domainCode);
        incident.setRiskLevel(resolveRiskLevel(decision));
        incident.setStatus(SecurityComplianceConstant.INCIDENT_STATUS_NEW);
        incident.setSourceType(sourceType);
        incident.setSourceId(sourceId);
        incident.setSourceName(sourceName);
        if (decision.getMatchedPolicies() != null && !decision.getMatchedPolicies().isEmpty()) {
            var first = decision.getMatchedPolicies().getFirst();
            incident.setPolicyId(first.getSourceId());
            incident.setPolicyCode(first.getSourceCode());
        }
        incident.setDecision(decision.getDecision());
        incident.setEventTitle(StringUtils.hasText(decision.getMessage()) ? decision.getMessage() : "命中安全策略");
        incident.setEventDetail(buildIncidentDetail(decision));
        incident.setPayload(Map.of(
                "userActionTip", safeValue(decision.getUserActionTip()),
                "adminActionTip", safeValue(decision.getAdminActionTip()),
                "matchedPolicies", decision.getMatchedPolicies() == null ? List.of() : decision.getMatchedPolicies(),
                "matchedRulePacks", decision.getMatchedRulePacks() == null ? List.of() : decision.getMatchedRulePacks()
        ));
        securityIncidentMapper.insert(incident);
        appendAction(incident, SecurityComplianceConstant.ACTION_COMMENT, "created", "系统自动创建安全事件", null, Map.of());
        return incident.getId();
    }

    private void resumeAgentExecution(SecurityIncident incident) {
        AgentExecution execution = agentExecutionMapper.selectById(incident.getSourceId());
        if (execution == null) {
            throw new BusinessException(ResultCode.AGENT_EXECUTION_NOT_FOUND);
        }
        if (!AgentExecutionStatusEnum.PAUSED.getCode().equals(execution.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前 Agent 执行记录不处于暂停状态");
        }
        Agent agent = agentMapper.selectById(execution.getAgentId());
        if (agent == null) {
            throw new BusinessException(ResultCode.AGENT_NOT_FOUND);
        }
        var update = new AgentExecution();
        update.setId(execution.getId());
        update.setErrorMessage(null);
        agentExecutionMapper.updateById(update);
        AgentExecutionInputDTO resumeInput = new AgentExecutionInputDTO();
        resumeInput.setMessage("安全事件已由管理员恢复，请在遵守当前安全约束的前提下继续未完成的任务。");
        resumeInput.setOptions(Map.of(
                "incidentId", incident.getId(),
                "traceId", incident.getTraceId()
        ));
        agentRuntimeOrchestratorProvider.getObject().resume(execution.getId(), resumeInput);
    }

    private SecurityIncident requireIncident(String id) {
        SecurityIncident incident = securityIncidentMapper.selectById(id);
        if (incident == null) {
            throw new BusinessException(ResultCode.SECURITY_INCIDENT_NOT_FOUND);
        }
        return incident;
    }

    private void appendAction(SecurityIncident incident,
                              String actionType,
                              String actionResult,
                              String comment,
                              SecurityAuditContext auditContext,
                              Map<String, Object> metadata) {
        var action = new SecurityIncidentAction();
        action.setTenantId(incident.getTenantId());
        action.setIncidentId(incident.getId());
        action.setActionType(actionType);
        action.setActionResult(actionResult);
        action.setComment(comment);
        action.setMetadata(metadata);
        action.setOperatorId(SecurityUtil.getCurrentUserId());
        action.setOperatorName(resolveUsername());
        action.setActionAt(LocalDateTime.now());
        securityIncidentActionMapper.insert(action);

        securityAuditEventService.recordEvent(
                incident.getTenantId(),
                incident.getTraceId(),
                SecurityComplianceConstant.EVENT_RUNTIME_CHECK,
                SecurityComplianceConstant.AUDIT_SOURCE_RUNTIME_ENGINE,
                SecurityComplianceConstant.AUDIT_STATUS_SUCCESS,
                incident.getRiskLevel(),
                incident.getDomainCode(),
                incident.getPolicyId(),
                incident.getPolicyCode(),
                SecurityComplianceConstant.RESOURCE_TYPE_INCIDENT,
                incident.getId(),
                "安全事件处置动作",
                "已执行安全事件动作: " + actionType,
                Map.of(
                        "incidentNo", incident.getIncidentNo(),
                        "actionType", actionType,
                        "actionResult", actionResult,
                        "comment", safeValue(comment)
                ),
                auditContext
        );
    }

    private SecurityIncidentVO toVO(SecurityIncident entity) {
        return toVO(entity, false);
    }

    private SecurityIncidentVO toVO(SecurityIncident entity, boolean includeActions) {
        var vo = new SecurityIncidentVO();
        vo.setId(entity.getId());
        vo.setTenantId(entity.getTenantId());
        vo.setIncidentNo(entity.getIncidentNo());
        vo.setTraceId(entity.getTraceId());
        vo.setDomainCode(entity.getDomainCode());
        vo.setRiskLevel(entity.getRiskLevel());
        vo.setStatus(entity.getStatus());
        vo.setSourceType(entity.getSourceType());
        vo.setSourceId(entity.getSourceId());
        vo.setSourceName(entity.getSourceName());
        vo.setPolicyId(entity.getPolicyId());
        vo.setPolicyCode(entity.getPolicyCode());
        vo.setDecision(entity.getDecision());
        vo.setEventTitle(entity.getEventTitle());
        vo.setEventDetail(entity.getEventDetail());
        vo.setAssignedTo(entity.getAssignedTo());
        vo.setAssignedName(entity.getAssignedName());
        vo.setResolvedAt(entity.getResolvedAt());
        vo.setResolutionSummary(entity.getResolutionSummary());
        vo.setPayload(entity.getPayload());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        if (includeActions) {
            vo.setActions(listActions(entity.getId()));
        }
        return vo;
    }

    private SecurityIncidentActionVO toActionVO(SecurityIncidentAction entity) {
        var vo = new SecurityIncidentActionVO();
        vo.setId(entity.getId());
        vo.setIncidentId(entity.getIncidentId());
        vo.setActionType(entity.getActionType());
        vo.setActionResult(entity.getActionResult());
        vo.setComment(entity.getComment());
        vo.setMetadata(entity.getMetadata());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorName(entity.getOperatorName());
        vo.setActionAt(entity.getActionAt());
        return vo;
    }

    private String buildIncidentNo() {
        return "INC-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private String buildIncidentDetail(SecurityCheckDecisionVO decision) {
        StringBuilder builder = new StringBuilder();
        builder.append("安全决策: ").append(safeValue(decision.getDecision()));
        if (StringUtils.hasText(decision.getUserActionTip())) {
            builder.append("\n用户提示: ").append(decision.getUserActionTip());
        }
        if (StringUtils.hasText(decision.getAdminActionTip())) {
            builder.append("\n管理员建议: ").append(decision.getAdminActionTip());
        }
        return builder.toString();
    }

    private String resolveRiskLevel(SecurityCheckDecisionVO decision) {
        if (decision.getMatchedPolicies() != null && !decision.getMatchedPolicies().isEmpty()) {
            return decision.getMatchedPolicies().getFirst().getRiskLevel();
        }
        if (decision.getMatchedRulePacks() != null && !decision.getMatchedRulePacks().isEmpty()) {
            return decision.getMatchedRulePacks().getFirst().getRiskLevel();
        }
        return SecurityComplianceConstant.RISK_LEVEL_MEDIUM;
    }

    private String resolveUsername() {
        if (StringUtils.hasText(SecurityUtil.getCurrentUsername())) {
            return SecurityUtil.getCurrentUsername();
        }
        return "system";
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }
}
