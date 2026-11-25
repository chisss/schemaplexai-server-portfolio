package com.schemaplexai.service.desktop;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.PermissionCodeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.service.agent.system.SystemAgentService;
import com.schemaplexai.service.desktop.DesktopEmployeeAccess.Employee;
import com.schemaplexai.service.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DesktopCapabilityService {

    private final AgentMapper agents;
    private final SkillMapper skills;
    private final SystemAgentService systemAgent;
    private final AuthorizationService authorization;
    private final DesktopEmployeeAccess access;
    private final JdbcTemplate jdbc;

    public record Bootstrap(String userId, String tenantId, Card defaultAgent, boolean canCreateSkill,
                            boolean canCreateExpert, boolean canRunWorkflow, boolean canManageWorkflow) {}

    public record Card(String id, String name, String description, String type, String status,
                       List<String> tags, String code, boolean canUse) {}

    public Bootstrap bootstrap() {
        Employee employee = Employee.current();
        Agent assistant = systemAgent.findSystemAgent(employee.tenantId());
        boolean agentManage = permission(PermissionCodeEnum.AGENT_MANAGE);
        return new Bootstrap(employee.userId(), employee.tenantId(),
                assistant != null && "active".equals(assistant.getStatus())
                        && permission(PermissionCodeEnum.AGENT_EXECUTE) ? expertCard(assistant) : null,
                agentManage, agentManage, permission(PermissionCodeEnum.WORKFLOW_EXECUTE),
                permission(PermissionCodeEnum.WORKFLOW_MANAGE));
    }

    public PageResult<Card> experts(String keyword, String type, int page, int size) {
        Employee employee = Employee.current();
        var query = new LambdaQueryWrapper<Agent>().eq(Agent::getTenantId, employee.tenantId());
        if (!permission(PermissionCodeEnum.AGENT_MANAGE)) {
            query.eq(Agent::getStatus, "active");
            query.and(q -> q.eq(Agent::getCreatedBy, employee.userId())
                    .or().eq(Agent::getSystemAgentCode, SystemAgentService.DEFAULT_ASSISTANT_CODE)
                    .or().apply("""
                            EXISTS (SELECT 1 FROM sf_desktop_resource_grant d
                                WHERE d.tenant_id = {0} AND d.user_id = {1} AND d.resource_type = 'agent'
                                  AND d.resource_id = CAST(sf_agent.id AS varchar))
                            """, employee.tenantId(), employee.userId()));
        }
        query.eq(StringUtils.hasText(type), Agent::getAgentType, type);
        if (StringUtils.hasText(keyword)) {
            query.and(q -> q.like(Agent::getName, keyword).or().like(Agent::getDescription, keyword)
                    .or().like(Agent::getAgentTag, keyword));
        }
        var result = agents.selectPage(new Page<>(page, size), query.orderByAsc(Agent::getName, Agent::getId));
        return new PageResult<>(result.getRecords().stream().map(this::expertCard).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public Card expert(String id) {
        return expertCard(requireExpert(id, false));
    }

    public void grant(String type, String id, String userId) {
        requireManage();
        Employee employee = Employee.current();
        requireActiveUser(employee, userId);
        switch (type) {
            case "agent" -> requireExpert(id, false);
            case "skill" -> {
                Skill skill = skills.selectById(id);
                if (skill == null || (skill.getTenantId() != null
                        && !employee.tenantId().equals(skill.getTenantId()))) {
                    throw new BusinessException(ResultCode.NOT_FOUND);
                }
            }
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持专家或技能授权");
        }
        access.grant(employee, type, id, userId);
    }

    public void revoke(String type, String id, String userId) {
        requireManage();
        Employee employee = Employee.current();
        if (!List.of("agent", "skill").contains(type)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持专家或技能授权");
        }
        access.revoke(employee, type, id, userId);
    }

    public Agent requireExpert(String id, boolean execute) {
        Employee employee = Employee.current();
        Agent agent = agents.selectById(id);
        if (agent == null || !employee.tenantId().equals(agent.getTenantId())) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        boolean availableToUser = permission(PermissionCodeEnum.AGENT_MANAGE)
                || employee.userId().equals(agent.getCreatedBy())
                || (Boolean.TRUE.equals(agent.getIsSystemAgent())
                && SystemAgentService.DEFAULT_ASSISTANT_CODE.equals(agent.getSystemAgentCode()))
                || access.granted(employee, "agent", id);
        if (!availableToUser || (execute && (!"active".equals(agent.getStatus())
                || !permission(PermissionCodeEnum.AGENT_EXECUTE)))) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前专家不可用");
        }
        if (!execute && !permission(PermissionCodeEnum.AGENT_MANAGE) && !"active".equals(agent.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return agent;
    }

    public PageResult<Card> skills(String keyword, String category, int page, int size) {
        Employee employee = Employee.current();
        var query = new LambdaQueryWrapper<Skill>()
                .and(q -> q.isNull(Skill::getTenantId).or().eq(Skill::getTenantId, employee.tenantId()));
        if (!permission(PermissionCodeEnum.AGENT_MANAGE)) {
            query.eq(Skill::getStatus, "active");
        }
        query.eq(StringUtils.hasText(category), Skill::getCategory, category);
        if (StringUtils.hasText(keyword)) {
            query.and(q -> q.like(Skill::getName, keyword).or().like(Skill::getDisplayName, keyword)
                    .or().like(Skill::getDescription, keyword));
        }
        var result = skills.selectPage(new Page<>(page, size), query.orderByAsc(Skill::getName, Skill::getId));
        return new PageResult<>(result.getRecords().stream().map(this::skillCard).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public Card skill(String id) {
        Employee employee = Employee.current();
        Skill skill = skills.selectById(id);
        if (skill == null || (skill.getTenantId() != null && !employee.tenantId().equals(skill.getTenantId()))
                || (!"active".equals(skill.getStatus()) && !permission(PermissionCodeEnum.AGENT_MANAGE))) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return skillCard(skill);
    }

    public void requireSkillCode(String code) {
        Employee employee = Employee.current();
        boolean enabled = skills.exists(new LambdaQueryWrapper<Skill>().eq(Skill::getName, code)
                .eq(Skill::getStatus, "active")
                .and(q -> q.isNull(Skill::getTenantId).or().eq(Skill::getTenantId, employee.tenantId())));
        if (!enabled) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前技能不可用");
        }
    }

    private Card expertCard(Agent agent) {
        List<String> tags = agent.getAgentTag() == null ? List.of()
                : Arrays.stream(agent.getAgentTag().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new Card(agent.getId(), agent.getName(), agent.getDescription(), agent.getAgentType(),
                agent.getStatus(), tags, null,
                "active".equals(agent.getStatus()) && permission(PermissionCodeEnum.AGENT_EXECUTE));
    }

    private Card skillCard(Skill skill) {
        String name = StringUtils.hasText(skill.getDisplayName()) ? skill.getDisplayName() : skill.getName();
        return new Card(skill.getId(), name, skill.getDescription(), skill.getCategory(),
                skill.getStatus(), List.of(), skill.getName(),
                "active".equals(skill.getStatus()) && permission(PermissionCodeEnum.AGENT_EXECUTE));
    }

    private boolean permission(PermissionCodeEnum permission) {
        return authorization.hasPermission(permission.getCode());
    }

    private void requireManage() {
        if (!permission(PermissionCodeEnum.AGENT_MANAGE)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    private void requireActiveUser(Employee employee, String userId) {
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM sf_user WHERE id=? AND tenant_id=? AND status='active' AND deleted=0)
                """, Boolean.class, userId, employee.tenantId()));
        if (!active) throw new BusinessException(ResultCode.NOT_FOUND, "员工不存在或不可用");
    }
}
