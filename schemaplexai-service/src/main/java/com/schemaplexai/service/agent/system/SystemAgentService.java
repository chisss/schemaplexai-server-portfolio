package com.schemaplexai.service.agent.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.BuiltinTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * 系统内置Agent管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemAgentService {

    public static final String DEFAULT_ASSISTANT_CODE = "default_assistant";
    private static final String DEFAULT_AGENT_NAME = "SchemaPlexAI 助手";
    private static final String DEFAULT_AGENT_DESC = "系统内置智能助手，具备完整工具调用、技能执行和上下文感知能力。支持全自动、计划、建议三种执行模式。";

    /** 排除的高危工具 */
    private static final Set<String> EXCLUDED_TOOL_CODES = Set.of("sys.rm", "sys.exec_dangerous");

    private final AgentMapper agentMapper;
    private final AgentToolBindingMapper toolBindingMapper;
    private final BuiltinToolMapper builtinToolMapper;

    /**
     * 获取租户的默认系统Agent，不存在则创建
     */
    @Transactional
    public Agent getOrCreateSystemAgent(String tenantId) {
        Agent existing = findSystemAgent(tenantId);
        if (existing != null) {
            return existing;
        }
        return createSystemAgent(tenantId);
    }

    /**
     * 查找租户的默认系统Agent
     */
    public Agent findSystemAgent(String tenantId) {
        return agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getTenantId, tenantId)
                .eq(Agent::getSystemAgentCode, DEFAULT_ASSISTANT_CODE)
                .eq(Agent::getIsSystemAgent, true));
    }

    /**
     * 创建系统Agent并绑定所有非高危内置工具
     */
    @Transactional
    public Agent createSystemAgent(String tenantId) {
        Agent agent = new Agent();
        agent.setTenantId(tenantId);
        agent.setName(DEFAULT_AGENT_NAME);
        agent.setAgentType("solo");
        agent.setDescription(DEFAULT_AGENT_DESC);
        agent.setStatus("active");
        agent.setMaxConcurrency(5);
        agent.setTriggerType("manual");
        agent.setConfigCompleted(true);
        agent.setIsSystemAgent(true);
        agent.setSystemAgentCode(DEFAULT_ASSISTANT_CODE);
        agent.setAgentTag("system,builtin,default");
        agentMapper.insert(agent);

        bindAllEnabledTools(tenantId, agent.getId());
        log.info("租户[{}]系统Agent创建成功: id={}", tenantId, agent.getId());
        return agent;
    }

    /**
     * 为系统Agent绑定所有启用的内置工具（排除高危工具）
     */
    @Transactional
    public void bindAllEnabledTools(String tenantId, String agentId) {
        List<BuiltinTool> enabledTools = builtinToolMapper.selectList(
                new LambdaQueryWrapper<BuiltinTool>()
                        .eq(BuiltinTool::getEnabled, true)
                        .notIn(BuiltinTool::getCode, EXCLUDED_TOOL_CODES));

        for (BuiltinTool tool : enabledTools) {
            boolean exists = toolBindingMapper.exists(new LambdaQueryWrapper<AgentToolBinding>()
                    .eq(AgentToolBinding::getAgentId, agentId)
                    .eq(AgentToolBinding::getToolCode, tool.getCode()));
            if (exists) {
                continue;
            }
            AgentToolBinding binding = new AgentToolBinding();
            binding.setTenantId(tenantId);
            binding.setAgentId(agentId);
            binding.setToolCode(tool.getCode());
            binding.setSourceType("builtin");
            binding.setEnabled(true);
            binding.setPriority(tool.getSortOrder());
            toolBindingMapper.insert(binding);
        }
        log.info("系统Agent[{}]绑定{}个内置工具", agentId, enabledTools.size());
    }

    /**
     * 刷新系统Agent的工具绑定（新增工具或MCP服务后调用）
     */
    @Transactional
    public void refreshToolBindings(String tenantId) {
        Agent agent = findSystemAgent(tenantId);
        if (agent == null) {
            log.warn("租户[{}]未找到系统Agent，跳过工具刷新", tenantId);
            return;
        }
        bindAllEnabledTools(tenantId, agent.getId());
    }

    /**
     * 解析系统Agent的模型（用户可覆盖）
     */
    public String resolveModel(String tenantId, String overrideModelId) {
        if (StringUtils.hasText(overrideModelId)) {
            return overrideModelId;
        }
        // 无覆盖时返回null，由AIModelRouter走租户默认模型逻辑
        return null;
    }

    /**
     * 校验是否为系统Agent（保护性校验）
     */
    public void guardSystemAgent(Agent agent, String operation) {
        if (Boolean.TRUE.equals(agent.getIsSystemAgent())) {
            throw new BusinessException(400, "系统内置Agent不允许" + operation);
        }
    }
}
