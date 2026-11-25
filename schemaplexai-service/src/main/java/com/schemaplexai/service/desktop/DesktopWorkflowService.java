package com.schemaplexai.service.desktop;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.PermissionCodeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.service.desktop.DesktopEmployeeAccess.Employee;
import com.schemaplexai.service.security.AuthorizationService;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DesktopWorkflowService {
    private final WorkflowTemplateMapper templates;
    private final WorkflowInstanceMapper instances;
    private final WorkflowInstanceService service;
    private final DesktopEmployeeAccess access;
    private final AuthorizationService authorization;
    private final JdbcTemplate jdbc;

    public record Template(String id, String name, String description, String category, String status,
                           boolean builtIn, boolean canRun, boolean canManage, LocalDateTime updatedAt) {}
    public record Run(String id, String templateId, String name, String status, LocalDateTime createdAt) {}

    public PageResult<Template> list(String keyword, String status, int page, int size) {
        Employee employee = Employee.current();
        var query = new LambdaQueryWrapper<WorkflowTemplate>()
                .eq(WorkflowTemplate::getTenantId, employee.tenantId())
                .eq(StringUtils.hasText(status), WorkflowTemplate::getStatus, status);
        if (!canManage()) {
            query.and(q -> q.eq(WorkflowTemplate::getCreatedBy, employee.userId()).or().apply("""
                    EXISTS (SELECT 1 FROM sf_desktop_resource_grant d
                        WHERE d.tenant_id={0} AND d.user_id={1} AND d.resource_type='workflow'
                        AND d.resource_id=CAST(sf_workflow_template.id AS varchar))
                    """, employee.tenantId(), employee.userId()));
        }
        if (StringUtils.hasText(keyword)) {
            query.and(q -> q.like(WorkflowTemplate::getName, keyword).or().like(WorkflowTemplate::getDescription, keyword));
        }
        var result = templates.selectPage(new Page<>(page, size),
                query.orderByDesc(WorkflowTemplate::getUpdatedAt).orderByAsc(WorkflowTemplate::getId));
        var cards = result.getRecords().stream().map(t -> new Template(t.getId(), t.getName(), t.getDescription(),
                t.getCategory(), t.getStatus(), Boolean.TRUE.equals(t.getIsBuiltin()),
                canExecute() && "published".equals(t.getStatus()),
                canManage() && !Boolean.TRUE.equals(t.getIsBuiltin()), t.getUpdatedAt())).toList();
        return new PageResult<>(cards, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public void grant(String workflowId, String userId) {
        if (!canManage()) throw new BusinessException(ResultCode.FORBIDDEN);
        Employee employee = Employee.current();
        requireTemplate(workflowId, false);
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM sf_user WHERE id=? AND tenant_id=? AND status='active' AND deleted=0)
                """, Boolean.class, userId, employee.tenantId()));
        if (!active) throw new BusinessException(ResultCode.NOT_FOUND, "员工不存在或不可用");
        access.grant(employee, "workflow", workflowId, userId);
    }

    public void revoke(String workflowId, String userId) {
        if (!canManage()) throw new BusinessException(ResultCode.FORBIDDEN);
        access.revoke(Employee.current(), "workflow", workflowId, userId);
    }


    public WorkflowTemplate requireTemplate(String id, boolean run) {
        Employee employee = Employee.current();
        WorkflowTemplate template = templates.selectById(id);
        if (template == null || !employee.tenantId().equals(template.getTenantId())) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        if (!canManage() && !employee.userId().equals(template.getCreatedBy())
                && !access.granted(employee, "workflow", id)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        if (run && (!canExecute() || !"published".equals(template.getStatus()))) {
            throw new BusinessException(ResultCode.FORBIDDEN, "工作流未发布或不可执行");
        }
        return template;
    }

    public WorkflowInstanceVO run(String templateId, String name, Map<String, Object> variables) {
        requireTemplate(templateId, true);
        WorkflowInstanceCreateRequest command = new WorkflowInstanceCreateRequest();
        command.setTemplateId(templateId);
        command.setName(name);
        command.setVariables(variables);
        return service.start(service.create(command).getId());
    }

    public PageResult<Run> runs(String templateId, int page, int size) {
        requireTemplate(templateId, false);
        Employee employee = Employee.current();
        var result = instances.selectPage(new Page<>(page, size), new LambdaQueryWrapper<WorkflowInstance>()
                .eq(WorkflowInstance::getTenantId, employee.tenantId())
                .eq(WorkflowInstance::getCreatedBy, employee.userId())
                .eq(WorkflowInstance::getTemplateId, templateId)
                .orderByDesc(WorkflowInstance::getCreatedAt).orderByAsc(WorkflowInstance::getId));
        return new PageResult<>(result.getRecords().stream().map(r -> new Run(r.getId(), r.getTemplateId(),
                r.getName(), r.getStatus(), r.getCreatedAt())).toList(), result.getTotal(), page, size);
    }

    public WorkflowInstance ownRun(String id) {
        Employee employee = Employee.current();
        var entity = service.requireEntity(id);
        if (!employee.tenantId().equals(entity.getTenantId()) || !employee.userId().equals(entity.getCreatedBy())) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        requireTemplate(entity.getTemplateId(), false);
        return entity;
    }

    private boolean canManage() {
        return authorization.hasPermission(PermissionCodeEnum.WORKFLOW_MANAGE.getCode());
    }

    private boolean canExecute() {
        return authorization.hasPermission(PermissionCodeEnum.WORKFLOW_EXECUTE.getCode());
    }
}
