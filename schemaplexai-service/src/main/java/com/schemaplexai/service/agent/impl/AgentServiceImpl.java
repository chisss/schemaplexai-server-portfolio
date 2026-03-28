package com.schemaplexai.service.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.AgentStatusEnum;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentConfigMapper;
import com.schemaplexai.dao.mapper.AgentContextBindingMapper;
import com.schemaplexai.dao.mapper.AgentExecutionLogMapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberToolBindingMapper;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.dao.mapper.ContextEntityMapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.model.entity.BuiltinTool;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.model.entity.ContextEntity;
import com.schemaplexai.model.entity.ContextItem;
import com.schemaplexai.model.converter.AgentConfigConverter;
import com.schemaplexai.model.converter.AgentContextBindingConverter;
import com.schemaplexai.model.converter.AgentConverter;
import com.schemaplexai.model.converter.AgentTeamMemberConverter;
import com.schemaplexai.model.converter.AgentToolBindingConverter;
import com.schemaplexai.model.dto.agent.AgentConfigRequest;
import com.schemaplexai.model.dto.agent.AgentContextBindingDTO;
import com.schemaplexai.model.dto.agent.AgentCreateRequest;
import com.schemaplexai.model.dto.agent.AgentExecuteDTO;
import com.schemaplexai.model.dto.agent.AgentExecutionQueryDTO;
import com.schemaplexai.model.dto.agent.AgentInitInstructionsDTO;
import com.schemaplexai.model.dto.agent.AgentQueryRequest;
import com.schemaplexai.model.dto.agent.AgentTeamMemberBatchRequest;
import com.schemaplexai.model.dto.agent.AgentToolBindingBatchRequest;
import com.schemaplexai.model.dto.agent.AgentUpdateRequest;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEvent;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.ExecutionEventStreamService;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentConfig;
import com.schemaplexai.model.entity.AgentContextBinding;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AgentExecutionLog;
import com.schemaplexai.model.entity.AgentTeamMember;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.AgentTeamMemberToolBinding;
import com.schemaplexai.model.entity.ChatMessageEntity;
import com.schemaplexai.model.vo.agent.AgentConfigVO;
import com.schemaplexai.model.vo.agent.AgentContextBindingVO;
import com.schemaplexai.model.vo.agent.AgentExecuteResultVO;
import com.schemaplexai.model.vo.agent.AgentExecutionLogVO;
import com.schemaplexai.model.vo.agent.AgentExecutionVO;
import com.schemaplexai.model.vo.agent.AgentInstructionsCheckVO;
import com.schemaplexai.model.vo.agent.AgentTeamMemberVO;
import com.schemaplexai.model.vo.agent.AgentTeamMemberToolBindingVO;
import com.schemaplexai.model.vo.agent.AgentToolBindingVO;
import com.schemaplexai.model.vo.agent.AgentVO;
import com.schemaplexai.model.vo.agent.AvailableToolVO;
import com.schemaplexai.model.vo.agent.ConversationMessageVO;
import com.schemaplexai.service.agent.handler.AgentConfigHandler;
import com.schemaplexai.service.agent.AgentService;
import com.schemaplexai.service.agent.validator.AgentValidator;
import com.schemaplexai.service.common.EntityValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent管理服务实现 — 编排器模式
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    public static final String QUEUED = AgentExecutionStatusEnum.QUEUED.getCode();
    private final AgentConfigMapper agentConfigMapper;
    private final AgentMapper agentMapper;
    private final AgentTeamMemberMapper agentTeamMemberMapper;
    private final AgentContextBindingMapper agentContextBindingMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentExecutionLogMapper agentExecutionLogMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final BuiltinToolMapper builtinToolMapper;
    private final SkillMapper skillMapper;
    private final McpServerMapper mcpServerMapper;
    private final AgentTeamMemberToolBindingMapper agentTeamMemberToolBindingMapper;
    private final AgentToolBindingMapper agentToolBindingMapper;
    private final AgentConverter agentConverter;
    private final AgentConfigConverter agentConfigConverter;
    private final AgentTeamMemberConverter agentTeamMemberConverter;
    private final AgentContextBindingConverter agentContextBindingConverter;
    private final AgentToolBindingConverter agentToolBindingConverter;
    private final AgentValidator agentValidator;
    private final AgentConfigHandler agentConfigHandler;
    private final EntityValidator entityValidator;
    private final ContextEntityMapper contextEntityMapper;
    private final ContextItemMapper contextItemMapper;
    private final AgentExecutionEngine agentExecutionEngine;
    private final ExecutionEventStreamService executionEventStreamService;

    @Override
    public PageResult<AgentVO> listAgents(AgentQueryRequest request) {
        var page = new Page<Agent>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<Agent>();

        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w
                    .like(Agent::getName, request.getKeyword())
                    .or()
                    .like(Agent::getDescription, request.getKeyword())
            );
        }
        if (StringUtils.hasText(request.getAgentType())) {
            wrapper.eq(Agent::getAgentType, request.getAgentType());
        }
        if (StringUtils.hasText(request.getStatus())) {
            // builtinPosition 场景下，忽略 status 过滤，始终返回该位置的内置 Agent
            if (!StringUtils.hasText(request.getBuiltinPosition())) {
                wrapper.eq(Agent::getStatus, request.getStatus());
            }
        }
        if (StringUtils.hasText(request.getBuiltinPosition())) {
            // 按内置位置筛选：匹配 sf_agent_config 中 config_key='builtin_position' AND config_value=? 的 Agent
            var builtinAgentIds = agentConfigMapper.selectList(
                    new LambdaQueryWrapper<AgentConfig>()
                            .eq(AgentConfig::getConfigKey, "builtin_position")
                            .eq(AgentConfig::getConfigValue, request.getBuiltinPosition()))
                    .stream()
                    .map(AgentConfig::getAgentId)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            if (builtinAgentIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L, request.getPage(), request.getSize());
            }
            wrapper.in(Agent::getId, builtinAgentIds);
        }
        if (StringUtils.hasText(request.getAgentTag())) {
            wrapper.like(Agent::getAgentTag, request.getAgentTag());
        }
        if (request.getIsBuiltin() != null) {
            if (Boolean.TRUE.equals(request.getIsBuiltin())) {
                wrapper.inSql(Agent::getId,
                        "SELECT agent_id FROM sf_agent_config WHERE config_key = 'is_builtin' AND lower(config_value) = 'true'");
            } else {
                wrapper.notInSql(Agent::getId,
                        "SELECT agent_id FROM sf_agent_config WHERE config_key = 'is_builtin' AND lower(config_value) = 'true'");
            }
        }
        wrapper.orderByDesc(Agent::getCreatedAt);

        var result = agentMapper.selectPage(page, wrapper);
        var voList = agentConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public List<AgentVO> listAllAgents() {
        var wrapper = new LambdaQueryWrapper<Agent>()
                .orderByDesc(Agent::getCreatedAt);
        var agents = agentMapper.selectList(wrapper);
        return agentConverter.toVOList(agents);
    }

    @Override
    public AgentVO getAgentById(String id) {
        var agent = entityValidator.requireExists(agentMapper, id, ResultCode.AGENT_NOT_FOUND);
        return enrichWithDetails(agent);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentVO createAgent(AgentCreateRequest request) {
        agentValidator.validateNameUnique(request.getName());

        var agent = agentConverter.fromCreateRequest(request);
        agent.setAgentTag(request.getAgentTag());
        agentMapper.insert(agent);

        // solo 类型：自动预绑定全部内置工具
        if ("solo".equalsIgnoreCase(agent.getAgentType())) {
            prebindBuiltinToolsForSoloAgent(agent);
        }

        log.info("创建Agent成功: agentId={}, name={}", agent.getId(), agent.getName());
        return enrichWithDetails(agent);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentVO updateAgent(String id, AgentUpdateRequest request) {
        var agent = entityValidator.requireExists(agentMapper, id, ResultCode.AGENT_NOT_FOUND);
        agentValidator.validateNotBusy(agent);

        // 名称变更时校验唯一性（排除自身）
        if (StringUtils.hasText(request.getName()) && !request.getName().equals(agent.getName())) {
            agentValidator.validateNameUnique(request.getName());
        }

        var updateEntity = new Agent();
        updateEntity.setId(id);
        updateEntity.setName(request.getName());
        updateEntity.setDescription(request.getDescription());
        updateEntity.setAiModel(request.getAiModel());
        updateEntity.setMaxConcurrency(request.getMaxConcurrency());
        updateEntity.setTriggerType(request.getTriggerType());
        updateEntity.setTriggerConfig(request.getTriggerConfig());
        updateEntity.setSkills(request.getSkills());
        updateEntity.setAgentTag(request.getAgentTag());
        updateEntity.setAiModelType(request.getAiModelType());
        updateEntity.setAiModelGroupId(request.getAiModelGroupId());
        agentMapper.updateById(updateEntity);

        log.info("更新Agent成功: agentId={}", id);
        return getAgentById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAgent(String id) {
        var agent = entityValidator.requireExists(agentMapper, id, ResultCode.AGENT_NOT_FOUND);
        agentValidator.validateNotBusy(agent);
        if (AgentStatusEnum.ACTIVE.getCode().equals(agent.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "激活状态的Agent不允许删除");
        }
        if (agentConfigHandler.isBuiltin(id)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "内置Agent不允许删除");
        }

        // 删除关联数据
        agentConfigHandler.deleteAllConfigs(id);
        // 先查团队成员 ID，再删成员工具绑定
        var members = agentTeamMemberMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMember>().eq(AgentTeamMember::getAgentId, id));
        if (!CollectionUtils.isEmpty(members)) {
            var memberIds = members.stream()
                    .map(AgentTeamMember::getId)
                    .filter(StringUtils::hasText)
                    .toList();
            if (!CollectionUtils.isEmpty(memberIds)) {
                agentTeamMemberToolBindingMapper.delete(
                        new LambdaQueryWrapper<AgentTeamMemberToolBinding>()
                                .in(AgentTeamMemberToolBinding::getMemberId, memberIds));
            }
        }
        agentTeamMemberMapper.delete(
                new LambdaQueryWrapper<AgentTeamMember>().eq(AgentTeamMember::getAgentId, id));
        agentContextBindingMapper.delete(
                new LambdaQueryWrapper<AgentContextBinding>().eq(AgentContextBinding::getAgentId, id));
        agentToolBindingMapper.delete(
                new LambdaQueryWrapper<AgentToolBinding>().eq(AgentToolBinding::getAgentId, id));
        agentMapper.deleteById(id);
        log.info("删除Agent成功: agentId={}", id);
    }

    /** 允许手动设置的状态值 */
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            AgentStatusEnum.ACTIVE.getCode(),
            AgentStatusEnum.INACTIVE.getCode()
    );

    @Override
    public void updateStatus(String id, String status) {
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
        var agent = entityValidator.requireExists(agentMapper, id, ResultCode.AGENT_NOT_FOUND);

        // 激活时校验配置是否完整
        if (AgentStatusEnum.ACTIVE.getCode().equals(status)) {
            Boolean configCompleted = agent.getConfigCompleted();
            if (configCompleted == null || !configCompleted) {
                throw new BusinessException(ResultCode.AGENT_CONFIG_INCOMPLETE);
            }
        }

        var updateEntity = new Agent();
        updateEntity.setId(id);
        updateEntity.setStatus(status);
        agentMapper.updateById(updateEntity);
        log.info("更新Agent状态: agentId={}, status={}", id, status);
    }

    @Override
    public List<AgentConfigVO> getConfigs(String agentId) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        var configs = agentConfigHandler.loadConfigs(agentId);
        return agentConfigConverter.toVOList(configs);
    }

    @Override
    public AgentConfigVO saveConfig(String agentId, AgentConfigRequest request) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        var config = agentConfigHandler.saveConfig(agentId, request);
        return agentConfigConverter.toVO(config);
    }

    @Override
    public void deleteConfig(String agentId, String configId) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        agentConfigHandler.deleteConfig(agentId, configId);
    }

    // ========== 团队成员 ==========

    @Override
    public List<AgentTeamMemberVO> getTeamMembers(String agentId) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        var members = agentTeamMemberMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMember>()
                        .eq(AgentTeamMember::getAgentId, agentId)
                        .orderByAsc(AgentTeamMember::getSortOrder));
        var memberVOs = agentTeamMemberConverter.toVOList(members);
        enrichTeamMemberBoundTools(memberVOs);
        return memberVOs;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AgentTeamMemberVO> saveTeamMembers(String agentId, AgentTeamMemberBatchRequest request) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);

        // 先清理旧成员的工具绑定
        var existingMembers = agentTeamMemberMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMember>().eq(AgentTeamMember::getAgentId, agentId));
        if (!CollectionUtils.isEmpty(existingMembers)) {
            var memberIds = existingMembers.stream()
                    .map(AgentTeamMember::getId)
                    .filter(StringUtils::hasText)
                    .toList();
            if (!CollectionUtils.isEmpty(memberIds)) {
                agentTeamMemberToolBindingMapper.delete(
                        new LambdaQueryWrapper<AgentTeamMemberToolBinding>()
                                .in(AgentTeamMemberToolBinding::getMemberId, memberIds));
            }
        }
        // 全量覆盖：先删后插
        agentTeamMemberMapper.delete(
                new LambdaQueryWrapper<AgentTeamMember>().eq(AgentTeamMember::getAgentId, agentId));

        if (!CollectionUtils.isEmpty(request.getMembers())) {
            for (int i = 0; i < request.getMembers().size(); i++) {
                var dto = request.getMembers().get(i);
                var member = new AgentTeamMember();
                member.setAgentId(agentId);
                member.setRoleName(dto.getRoleName());
                member.setRoleType(dto.getRoleType());
                member.setQuantity(dto.getQuantity());
                member.setModelOverride(dto.getModelOverride());
                member.setDescription(dto.getDescription());
                member.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : i);
                agentTeamMemberMapper.insert(member);
            }
        }

        // 同步更新 workType
        if (StringUtils.hasText(request.getWorkType())) {
            var updateAgent = new Agent();
            updateAgent.setId(agentId);
            updateAgent.setWorkType(request.getWorkType());
            agentMapper.updateById(updateAgent);
        }
        // 回算配置完成状态（team 类型需同时满足团队成员和上下文绑定）
        recomputeConfigCompleted(agentId);

        log.info("批量保存团队成员成功: agentId={}, count={}", agentId,
                request.getMembers() != null ? request.getMembers().size() : 0);
        return getTeamMembers(agentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTeamMember(String agentId, String memberId) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        var member = agentTeamMemberMapper.selectOne(
                new LambdaQueryWrapper<AgentTeamMember>()
                        .eq(AgentTeamMember::getId, memberId)
                        .eq(AgentTeamMember::getAgentId, agentId));
        if (member == null) {
            throw new BusinessException(ResultCode.AGENT_TEAM_MEMBER_NOT_FOUND);
        }
        // 清理成员的工具绑定
        agentTeamMemberToolBindingMapper.delete(
                new LambdaQueryWrapper<AgentTeamMemberToolBinding>()
                        .eq(AgentTeamMemberToolBinding::getMemberId, memberId));
        agentTeamMemberMapper.deleteById(memberId);
        // 成员删除后回算配置完成状态
        recomputeConfigCompleted(agentId);
        log.info("删除团队成员成功: agentId={}, memberId={}", agentId, memberId);
    }

    // ========== 上下文绑定 ==========

    @Override
    public List<AgentContextBindingVO> getContextBindings(String agentId) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        var bindings = agentContextBindingMapper.selectList(
                new LambdaQueryWrapper<AgentContextBinding>()
                        .eq(AgentContextBinding::getAgentId, agentId)
                        .orderByAsc(AgentContextBinding::getSortOrder));
        return agentContextBindingConverter.toVOList(bindings);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentContextBindingVO createContextBinding(String agentId, AgentContextBindingDTO request) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);

        // 手工输入上下文时，先将内容保存到上下文管理模块
        String contextId = request.getContextId();
        if ("manual".equals(request.getSourceType()) && StringUtils.hasText(request.getContent())) {
            // 创建上下文实体
            var context = new ContextEntity();
            context.setName(StringUtils.hasText(request.getTitle()) ? request.getTitle() : "Agent 手工上下文");
            context.setContextLevel("agent");
            context.setProjectId(agentId);
            context.setStatus(CommonConstant.STATUS_ACTIVE);
            contextEntityMapper.insert(context);

            // 创建上下文条目
            var item = new ContextItem();
            item.setContextId(context.getId());
            item.setItemType("document");
            item.setTitle(request.getTitle());
            item.setContent(request.getContent());
            item.setSortOrder(0);
            contextItemMapper.insert(item);

            contextId = context.getId();
            log.info("手工输入上下文已保存到上下文模块: contextId={}, agentId={}", contextId, agentId);
        }

        var binding = new AgentContextBinding();
        binding.setAgentId(agentId);
        binding.setContextId(contextId);
        binding.setSourceType(request.getSourceType());
        binding.setSourceConfig(request.getSourceConfig());
        binding.setContent(request.getContent());
        binding.setTitle(request.getTitle());
        binding.setSortOrder(request.getSortOrder());
        binding.setStatus(CommonConstant.STATUS_ACTIVE);
        agentContextBindingMapper.insert(binding);

        // 绑定创建后回算配置完成状态
        recomputeConfigCompleted(agentId);
        log.info("创建上下文绑定成功: agentId={}, bindingId={}", agentId, binding.getId());
        return agentContextBindingConverter.toVO(binding);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteContextBinding(String agentId, String bindingId) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        var binding = agentContextBindingMapper.selectOne(
                new LambdaQueryWrapper<AgentContextBinding>()
                        .eq(AgentContextBinding::getId, bindingId)
                        .eq(AgentContextBinding::getAgentId, agentId));
        if (binding == null) {
            throw new BusinessException(ResultCode.AGENT_CONTEXT_BINDING_NOT_FOUND);
        }
        agentContextBindingMapper.deleteById(bindingId);
        // 绑定删除后回算配置完成状态
        recomputeConfigCompleted(agentId);
        log.info("删除上下文绑定成功: agentId={}, bindingId={}", agentId, bindingId);
    }

    // ========== 工具绑定 ==========

    @Override
    public List<AgentToolBindingVO> getToolBindings(String agentId) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        var bindings = agentToolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agentId)
                        .orderByAsc(AgentToolBinding::getPriority)
                        .orderByAsc(AgentToolBinding::getCreatedAt)
        );
        return agentToolBindingConverter.toVOList(bindings);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AgentToolBindingVO> saveToolBindings(String agentId, AgentToolBindingBatchRequest request) {
        var agent = entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        String tenantId = StringUtils.hasText(SecurityUtil.getCurrentTenantId())
                ? SecurityUtil.getCurrentTenantId() : agent.getTenantId();

        agentToolBindingMapper.delete(new LambdaQueryWrapper<AgentToolBinding>()
                .eq(AgentToolBinding::getAgentId, agentId));

        if (request != null && !CollectionUtils.isEmpty(request.getTools())) {
            Set<String> deduplicatedCodes = new HashSet<>();
            for (var item : request.getTools()) {
                if (item == null || !StringUtils.hasText(item.getToolCode())) {
                    continue;
                }
                String normalizedCode = item.getToolCode().trim();
                if (!deduplicatedCodes.add(normalizedCode)) {
                    continue;
                }

                String sourceType = StringUtils.hasText(item.getSourceType()) ? item.getSourceType().trim() : "builtin";
                if ("mcp".equals(sourceType) && !StringUtils.hasText(item.getSourceRefId())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "MCP 工具必须指定 sourceRefId");
                }
                if ("skill".equals(sourceType) && !StringUtils.hasText(item.getSourceRefId())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "Skill 工具必须指定 sourceRefId");
                }

                var binding = new AgentToolBinding();
                binding.setTenantId(tenantId);
                binding.setAgentId(agentId);
                binding.setToolCode(normalizedCode);
                binding.setSourceType(sourceType);
                binding.setSourceRefId(item.getSourceRefId());
                binding.setEnabled(item.getEnabled() == null || item.getEnabled());
                binding.setPriority(item.getPriority() == null ? 100 : item.getPriority());
                binding.setConfigOverride(item.getConfigOverride());
                agentToolBindingMapper.insert(binding);
            }
        }

        log.info("批量保存 Agent 工具绑定成功: agentId={}, count={}", agentId,
                request != null && request.getTools() != null ? request.getTools().size() : 0);
        return getToolBindings(agentId);
    }

    @Override
    public List<AvailableToolVO> getAvailableTools(String agentId) {
        var agent = entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);

        // 获取已绑定工具的 key 集合
        var boundBindings = agentToolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agentId));
        Set<String> boundKeys = boundBindings.stream()
                .map(this::buildToolKey)
                .collect(Collectors.toSet());

        Map<String, AvailableToolVO> mergedTools = new LinkedHashMap<>();
        for (AvailableToolVO tool : loadBuiltinTools(boundKeys)) {
            mergedTools.putIfAbsent(buildToolKey(tool.getSourceType(), tool.getSourceRefId(), tool.getToolCode()), tool);
        }
        for (AvailableToolVO tool : loadSkillTools(agent.getTenantId(), boundKeys)) {
            mergedTools.putIfAbsent(buildToolKey(tool.getSourceType(), tool.getSourceRefId(), tool.getToolCode()), tool);
        }
        for (AvailableToolVO tool : loadMcpTools(agent.getTenantId(), boundKeys)) {
            mergedTools.putIfAbsent(buildToolKey(tool.getSourceType(), tool.getSourceRefId(), tool.getToolCode()), tool);
        }
        return new ArrayList<>(mergedTools.values());
    }

    private void prebindBuiltinToolsForSoloAgent(Agent agent) {
        var builtinTools = builtinToolMapper.selectList(
                new LambdaQueryWrapper<BuiltinTool>()
                        .eq(BuiltinTool::getEnabled, true)
                        .orderByAsc(BuiltinTool::getSortOrder));
        if (CollectionUtils.isEmpty(builtinTools)) {
            return;
        }

        String tenantId = StringUtils.hasText(SecurityUtil.getCurrentTenantId())
                ? SecurityUtil.getCurrentTenantId() : agent.getTenantId();
        Set<String> deduplicatedCodes = new HashSet<>();
        int priority = 100;
        for (BuiltinTool builtinTool : builtinTools) {
            if (!StringUtils.hasText(builtinTool.getCode())) {
                continue;
            }
            String toolCode = builtinTool.getCode().trim();
            if (!deduplicatedCodes.add(toolCode)) {
                continue;
            }
            var binding = new AgentToolBinding();
            binding.setTenantId(tenantId);
            binding.setAgentId(agent.getId());
            binding.setToolCode(toolCode);
            binding.setSourceType("builtin");
            binding.setEnabled(true);
            binding.setPriority(priority++);
            agentToolBindingMapper.insert(binding);
        }
        log.info("Solo Agent 预绑定内置工具完成: agentId={}, count={}", agent.getId(), deduplicatedCodes.size());
    }

    private List<AvailableToolVO> loadBuiltinTools(Set<String> boundKeys) {
        var builtinTools = builtinToolMapper.selectList(
                new LambdaQueryWrapper<BuiltinTool>()
                        .eq(BuiltinTool::getEnabled, true)
                        .orderByAsc(BuiltinTool::getSortOrder));

        return builtinTools.stream()
                .filter(tool -> StringUtils.hasText(tool.getCode()))
                .map(tool -> {
                    var availableTool = new AvailableToolVO();
                    availableTool.setToolCode(tool.getCode().trim());
                    availableTool.setName(tool.getName());
                    availableTool.setDescription(tool.getDescription());
                    availableTool.setInputSchema(tool.getInputSchema());
                    availableTool.setOsSupport(tool.getOsSupport());
                    availableTool.setSourceType("builtin");
                    availableTool.setSourceRefId(null);
                    availableTool.setAlreadyBound(boundKeys.contains(
                            buildToolKey("builtin", null, tool.getCode())));
                    return availableTool;
                })
                .toList();
    }

    private List<AvailableToolVO> loadSkillTools(String tenantId, Set<String> boundKeys) {
        var wrapper = new LambdaQueryWrapper<Skill>()
                .eq(Skill::getStatus, CommonConstant.STATUS_ACTIVE)
                .orderByAsc(Skill::getDisplayName)
                .orderByAsc(Skill::getName);
        if (StringUtils.hasText(tenantId)) {
            wrapper.and(w -> w.eq(Skill::getTenantId, tenantId).or().isNull(Skill::getTenantId));
        } else {
            wrapper.isNull(Skill::getTenantId);
        }

        var skills = skillMapper.selectList(wrapper);
        Map<String, AvailableToolVO> deduplicated = new LinkedHashMap<>();
        for (Skill skill : skills) {
            if (!StringUtils.hasText(skill.getName())) {
                continue;
            }
            String toolCode = skill.getName().trim();
            String key = buildToolKey("skill", skill.getId(), toolCode);
            if (deduplicated.containsKey(key)) {
                continue;
            }
            var availableTool = new AvailableToolVO();
            availableTool.setToolCode(toolCode);
            availableTool.setName(StringUtils.hasText(skill.getDisplayName()) ? skill.getDisplayName() : toolCode);
            availableTool.setDescription(skill.getDescription());
            availableTool.setSourceType("skill");
            availableTool.setSourceRefId(skill.getId());
            availableTool.setAlreadyBound(boundKeys.contains(key));
            deduplicated.put(key, availableTool);
        }
        return new ArrayList<>(deduplicated.values());
    }

    private List<AvailableToolVO> loadMcpTools(String tenantId, Set<String> boundKeys) {
        if (!StringUtils.hasText(tenantId)) {
            return List.of();
        }
        var mcpServers = mcpServerMapper.selectList(
                new LambdaQueryWrapper<McpServer>()
                        .eq(McpServer::getTenantId, tenantId)
                        .eq(McpServer::getStatus, CommonConstant.STATUS_ACTIVE)
                        .orderByAsc(McpServer::getName));

        List<AvailableToolVO> results = new ArrayList<>();
        Set<String> deduplicatedKeys = new HashSet<>();
        for (McpServer mcpServer : mcpServers) {
            if (CollectionUtils.isEmpty(mcpServer.getTools())) {
                continue;
            }
            for (Object toolObject : mcpServer.getTools()) {
                if (!(toolObject instanceof Map<?, ?> toolMap)) {
                    continue;
                }
                Object toolNameObj = toolMap.get("name");
                if (toolNameObj == null || !StringUtils.hasText(String.valueOf(toolNameObj))) {
                    continue;
                }
                String toolCode = String.valueOf(toolNameObj).trim();
                String key = buildToolKey("mcp", mcpServer.getId(), toolCode);
                if (!deduplicatedKeys.add(key)) {
                    continue;
                }
                var availableTool = new AvailableToolVO();
                availableTool.setToolCode(toolCode);
                availableTool.setName(toolCode);
                Object description = toolMap.get("description");
                availableTool.setDescription(description != null ? String.valueOf(description) : null);
                Object inputSchema = toolMap.get("inputSchema");
                if (inputSchema == null) {
                    inputSchema = toolMap.get("input_schema");
                }
                availableTool.setInputSchema(inputSchema != null ? String.valueOf(inputSchema) : null);
                availableTool.setSourceType("mcp");
                availableTool.setSourceRefId(mcpServer.getId());
                availableTool.setAlreadyBound(boundKeys.contains(key));
                results.add(availableTool);
            }
        }
        return results;
    }

    private String buildToolKey(AgentToolBinding binding) {
        if (binding == null) {
            return "builtin||";
        }
        return buildToolKey(binding.getSourceType(), binding.getSourceRefId(), binding.getToolCode());
    }

    private String buildToolKey(String sourceType, String sourceRefId, String toolCode) {
        String normalizedSourceType = StringUtils.hasText(sourceType) ? sourceType.trim().toLowerCase() : "builtin";
        String normalizedSourceRefId = StringUtils.hasText(sourceRefId) ? sourceRefId.trim() : "";
        String normalizedToolCode = StringUtils.hasText(toolCode) ? toolCode.trim().toLowerCase() : "";
        return normalizedSourceType + "|" + normalizedSourceRefId + "|" + normalizedToolCode;
    }

    private void enrichTeamMemberBoundTools(List<AgentTeamMemberVO> memberVOs) {
        if (CollectionUtils.isEmpty(memberVOs)) {
            return;
        }
        var memberIds = memberVOs.stream()
                .map(AgentTeamMemberVO::getId)
                .filter(StringUtils::hasText)
                .toList();
        if (CollectionUtils.isEmpty(memberIds)) {
            return;
        }
        var bindings = agentTeamMemberToolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMemberToolBinding>()
                        .in(AgentTeamMemberToolBinding::getMemberId, memberIds)
                        .orderByAsc(AgentTeamMemberToolBinding::getPriority)
                        .orderByAsc(AgentTeamMemberToolBinding::getCreatedAt));

        var grouped = bindings.stream()
                .map(this::toMemberToolBindingVO)
                .collect(Collectors.groupingBy(
                        AgentTeamMemberToolBindingVO::getMemberId,
                        LinkedHashMap::new,
                        Collectors.toList()));

        for (AgentTeamMemberVO memberVO : memberVOs) {
            var memberTools = grouped.getOrDefault(memberVO.getId(), List.of());
            memberVO.setBoundTools(memberTools);
            memberVO.setBoundToolCount(memberTools.size());
            String toolNames = memberTools.stream()
                    .map(AgentTeamMemberToolBindingVO::getToolCode)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .collect(Collectors.joining(","));
            memberVO.setBoundToolNames(StringUtils.hasText(toolNames) ? toolNames : null);
        }
    }

    private AgentTeamMemberToolBindingVO toMemberToolBindingVO(AgentTeamMemberToolBinding binding) {
        var vo = new AgentTeamMemberToolBindingVO();
        vo.setId(binding.getId());
        vo.setMemberId(binding.getMemberId());
        vo.setToolCode(binding.getToolCode());
        vo.setSourceType(binding.getSourceType());
        vo.setSourceRefId(binding.getSourceRefId());
        vo.setEnabled(binding.getEnabled());
        vo.setPriority(binding.getPriority());
        vo.setConfigOverride(binding.getConfigOverride());
        vo.setCreatedAt(binding.getCreatedAt());
        return vo;
    }

    /**
     * 回算 Agent 配置完成状态
     * 规则：
     *   - team 类型：需有团队成员 AND 上下文绑定
     *   - solo 类型：需有上下文绑定
     */
    private void recomputeConfigCompleted(String agentId) {
        var agent = agentMapper.selectById(agentId);
        if (agent == null) return;

        long contextBindingCount = agentContextBindingMapper.selectCount(
                new LambdaQueryWrapper<AgentContextBinding>()
                        .eq(AgentContextBinding::getAgentId, agentId));
        boolean contextBindingDone = contextBindingCount > 0;

        boolean completed;
        if ("team".equals(agent.getAgentType())) {
            long memberCount = agentTeamMemberMapper.selectCount(
                    new LambdaQueryWrapper<AgentTeamMember>()
                            .eq(AgentTeamMember::getAgentId, agentId));
            completed = memberCount > 0 && contextBindingDone;
        } else {
            // solo 类型：只需绑定上下文
            completed = contextBindingDone;
        }
        var update = new Agent();
        update.setId(agentId);
        update.setConfigCompleted(completed);
        agentMapper.updateById(update);
        log.debug("回算 configCompleted: agentId={}, agentType={}, result={}", agentId, agent.getAgentType(), completed);
    }

    /**
     * 为AgentVO附加配置、团队成员和上下文绑定
     */
    private AgentVO enrichWithDetails(Agent agent) {
        var vo = agentConverter.toVO(agent);
        // 附加配置
        var configs = agentConfigHandler.loadConfigs(agent.getId());
        vo.setConfigs(agentConfigConverter.toVOList(configs));
        // 回填 builtin 信息
        vo.setBuiltin(configs.stream().anyMatch(c ->
                "is_builtin".equals(c.getConfigKey()) && "true".equalsIgnoreCase(c.getConfigValue())));
        var builtinPositions = configs.stream()
                .filter(c -> "builtin_position".equals(c.getConfigKey()))
                .map(AgentConfig::getConfigValue)
                .filter(StringUtils::hasText)
                .flatMap(v -> Arrays.stream(v.split(",")))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        vo.setBuiltinPositions(builtinPositions);
        vo.setAgentTag(agent.getAgentTag());
        // 附加团队成员（含工具绑定信息）
        var members = agentTeamMemberMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMember>()
                        .eq(AgentTeamMember::getAgentId, agent.getId())
                        .orderByAsc(AgentTeamMember::getSortOrder));
        var memberVOs = agentTeamMemberConverter.toVOList(members);
        enrichTeamMemberBoundTools(memberVOs);
        vo.setTeamMembers(memberVOs);
        // 附加上下文绑定
        var bindings = agentContextBindingMapper.selectList(
                new LambdaQueryWrapper<AgentContextBinding>()
                        .eq(AgentContextBinding::getAgentId, agent.getId())
                        .orderByAsc(AgentContextBinding::getSortOrder));
        vo.setContextBindings(agentContextBindingConverter.toVOList(bindings));
        // 填充细粒度配置状态字段，供前端精确判断并展示对应提示
        boolean contextBindingDone = !bindings.isEmpty();
        vo.setContextBindingDone(contextBindingDone);
        if ("team".equals(agent.getAgentType())) {
            vo.setTeamConfigDone(!members.isEmpty());
        } else {
            // solo 类型不涉及团队配置，设为 true 以避免前端误判
            vo.setTeamConfigDone(true);
        }
        return vo;
    }

    // ==================== Agent 执行 ====================

    @Override
    public AgentExecuteResultVO execute(String agentId, AgentExecuteDTO dto) {
        var agent = entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        if (!AgentStatusEnum.ACTIVE.getCode().equals(agent.getStatus())) {
            throw new BusinessException(ResultCode.AGENT_INACTIVE);
        }

        // 先提交插入，再触发异步引擎，避免 @Transactional 未提交时异步线程读不到记录
        var execution = new AgentExecution();
        execution.setAgentId(agentId);
        execution.setInputPrompt(dto.getPrompt());
        execution.setInputContext(dto.getContext());
        execution.setAiModel(StringUtils.hasText(dto.getModel()) ? dto.getModel() : agent.getAiModel());
        execution.setConversationId(dto.getConversationId());
        execution.setStatus(QUEUED);
        agentExecutionMapper.insert(execution);
        executionEventStreamService.publish(AgentExecutionEvent.builder()
                .eventType("QUEUED")
                .executionId(execution.getId())
                .message("执行已加入队列")
                .timestamp(Instant.now())
                .build());

        // 入队后立即异步触发执行引擎（insert 已自动提交，异步线程可直接读到该记录）
        agentExecutionEngine.execute(AgentExecutionContext.builder()
                .executionId(execution.getId())
                .agentId(agentId)
                .tenantId(SecurityUtil.getCurrentTenantId())
                .inputPrompt(dto.getPrompt())
                .model(execution.getAiModel())
                .agentModelType(agent.getAiModelType())
                .agentModelGroupId(agent.getAiModelGroupId())
                .inputContext(dto.getContext())
                .conversationId(execution.getConversationId())
                .stream(Boolean.TRUE.equals(dto.getStream()))
                .build());

        log.info("Agent执行任务已入队: agentId={}, executionId={}", agentId, execution.getId());
        return AgentExecuteResultVO.builder()
                .executionId(execution.getId())
                .conversationId(execution.getConversationId())
                .status(QUEUED)
                .queuedAt(execution.getCreatedAt())
                .message("执行任务已提交，等待调度")
                .build();
    }

    @Override
    public PageResult<AgentExecutionVO> pageExecutions(String agentId, AgentExecutionQueryDTO query) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);

        var page = new Page<AgentExecution>(query.getPage(), query.getSize());
        var wrapper = new LambdaQueryWrapper<AgentExecution>()
                .eq(AgentExecution::getAgentId, agentId);
        if (StringUtils.hasText(query.getExecutionId())) {
            wrapper.eq(AgentExecution::getId, query.getExecutionId());
        }
        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(AgentExecution::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(AgentExecution::getCreatedAt);

        var result = agentExecutionMapper.selectPage(page, wrapper);
        var voList = result.getRecords().stream()
                .map(this::toExecutionVO)
                .toList();
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public AgentExecutionVO getExecution(String agentId, String executionId) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        var execution = agentExecutionMapper.selectOne(
                new LambdaQueryWrapper<AgentExecution>()
                        .eq(AgentExecution::getId, executionId)
                        .eq(AgentExecution::getAgentId, agentId));
        if (execution == null) {
            throw new BusinessException(ResultCode.AGENT_EXECUTION_NOT_FOUND);
        }
        var vo = toExecutionVO(execution);
        // 附加执行日志
        var logs = agentExecutionLogMapper.selectList(
                new LambdaQueryWrapper<AgentExecutionLog>()
                        .eq(AgentExecutionLog::getExecutionId, executionId)
                        .orderByAsc(AgentExecutionLog::getCreatedAt));
        vo.setLogs(logs.stream().map(this::toLogVO).toList());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void stopExecution(String agentId, String executionId) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        var execution = agentExecutionMapper.selectOne(
                new LambdaQueryWrapper<AgentExecution>()
                        .eq(AgentExecution::getId, executionId)
                        .eq(AgentExecution::getAgentId, agentId));
        if (execution == null) {
            throw new BusinessException(ResultCode.AGENT_EXECUTION_NOT_FOUND);
        }
        if (!QUEUED.equals(execution.getStatus()) && !AgentExecutionStatusEnum.RUNNING.getCode().equals(execution.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
        var update = new AgentExecution();
        update.setId(executionId);
        update.setStatus(AgentExecutionStatusEnum.STOPPED.getCode());
        update.setCompletedAt(LocalDateTime.now());
        agentExecutionMapper.updateById(update);
        executionEventStreamService.publish(AgentExecutionEvent.builder()
                .eventType("CANCELLED")
                .executionId(executionId)
                .message("执行已取消")
                .timestamp(Instant.now())
                .build());
        log.info("停止Agent执行: agentId={}, executionId={}", agentId, executionId);
    }

    @Override
    public List<ConversationMessageVO> getConversationHistory(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return List.of();
        }
        List<ChatMessageEntity> entities = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .orderByAsc(ChatMessageEntity::getMessageIndex));
        return entities.stream()
                .map(this::toConversationMessageVO)
                .toList();
    }

    private ConversationMessageVO toConversationMessageVO(ChatMessageEntity entity) {
        return ConversationMessageVO.builder()
                .messageType(entity.getMessageType())
                .textContent(entity.getTextContent())
                .toolCallId(entity.getToolCallId())
                .toolName(entity.getToolName())
                .messageIndex(entity.getMessageIndex())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private AgentExecutionVO toExecutionVO(AgentExecution execution) {
        var vo = new AgentExecutionVO();
        vo.setExecutionId(execution.getId());
        vo.setAgentId(execution.getAgentId());
        vo.setStatus(execution.getStatus());
        vo.setInputPrompt(execution.getInputPrompt());
        vo.setModel(execution.getAiModel());
        vo.setTokenInput(execution.getTokenInput());
        vo.setTokenOutput(execution.getTokenOutput());
        vo.setErrorMessage(execution.getErrorMessage());
        vo.setCreatedAt(execution.getCreatedAt());
        vo.setCompletedAt(execution.getCompletedAt());
        return vo;
    }

    private AgentExecutionLogVO toLogVO(AgentExecutionLog log) {
        var vo = new AgentExecutionLogVO();
        vo.setLogLevel(log.getLogLevel());
        vo.setLogType(log.getLogType());
        vo.setRoundNum(log.getRoundNum());
        vo.setToolName(log.getToolName());
        vo.setContent(log.getContent());
        vo.setTokenDelta(log.getTokenDelta());
        vo.setElapsedMs(log.getElapsedMs());
        vo.setCreatedAt(log.getCreatedAt());
        return vo;
    }

    // ==================== Agent 专属指令 ====================

    @Override
    public AgentInstructionsCheckVO checkInstructions(String agentId) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        List<AgentContextBinding> bindings = agentContextBindingMapper.selectList(
                new LambdaQueryWrapper<AgentContextBinding>()
                        .eq(AgentContextBinding::getAgentId, agentId));
        // 收集全部 contextId，批量查询，避免 N+1
        List<String> contextIds = bindings.stream()
                .map(AgentContextBinding::getContextId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (CollectionUtils.isEmpty(contextIds)) {
            return AgentInstructionsCheckVO.builder().hasInstructions(false).build();
        }
        ContextItem item = contextItemMapper.selectOne(
                new LambdaQueryWrapper<ContextItem>()
                        .in(ContextItem::getContextId, contextIds)
                        .eq(ContextItem::getIsAgentInstructions, true)
                        .orderByAsc(ContextItem::getCreatedAt)
                        .last("LIMIT 1"));
        if (item != null) {
            return AgentInstructionsCheckVO.builder()
                    .hasInstructions(true)
                    .itemId(item.getId())
                    .contextId(item.getContextId())
                    .title(item.getTitle())
                    .build();
        }
        return AgentInstructionsCheckVO.builder().hasInstructions(false).build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentInstructionsCheckVO initInstructions(String agentId, AgentInitInstructionsDTO dto) {
        entityValidator.requireExists(agentMapper, agentId, ResultCode.AGENT_NOT_FOUND);
        // 幂等性检查：已有专属指令则直接返回
        AgentInstructionsCheckVO existing = checkInstructions(agentId);
        if (existing.isHasInstructions()) {
            return existing;
        }
        // 查找该 Agent 第一个上下文绑定
        AgentContextBinding binding = agentContextBindingMapper.selectOne(
                new LambdaQueryWrapper<AgentContextBinding>()
                        .eq(AgentContextBinding::getAgentId, agentId)
                        .isNotNull(AgentContextBinding::getContextId)
                        .last("LIMIT 1"));
        if (binding == null) {
            throw new BusinessException(ResultCode.CONTEXT_NOT_FOUND);
        }
        String contextId = binding.getContextId();
        String title = org.springframework.util.StringUtils.hasText(dto.getTitle())
                ? dto.getTitle() : resolveTemplateTitle(dto.getTemplate());
        String content = resolveTemplateContent(dto.getTemplate());

        ContextItem item = new ContextItem();
        item.setContextId(contextId);
        item.setItemType("config");
        item.setTitle(title);
        item.setContent(content);
        item.setIsAgentInstructions(true);
        item.setSortOrder(0);
        contextItemMapper.insert(item);
        log.info("初始化Agent专属指令: agentId={}, itemId={}", agentId, item.getId());

        return AgentInstructionsCheckVO.builder()
                .hasInstructions(true)
                .itemId(item.getId())
                .contextId(contextId)
                .title(title)
                .build();
    }

    private String resolveTemplateTitle(String template) {
        return switch (template) {
            case "claude" -> "Claude 系统提示词（Agent专属指令）";
            case "openai" -> "OpenAI 系统提示词（Agent专属指令）";
            default       -> "Agent 系统提示词（通用）";
        };
    }

    private String resolveTemplateContent(String template) {
        return switch (template) {
            case "claude" -> """
                    You are a helpful AI assistant. Your role is to assist users by:
                    - Understanding their requests clearly
                    - Providing accurate and actionable responses
                    - Using available tools effectively when needed
                    - Communicating results in a clear, structured format

                    Always think step by step before taking action.
                    """;
            case "openai" -> """
                    You are an intelligent assistant. Follow these guidelines:
                    1. Be concise and direct in your responses.
                    2. Use tools when they can help accomplish the task.
                    3. Always verify your work before providing a final answer.
                    4. If uncertain, acknowledge it and ask for clarification.
                    """;
            default       -> """
                    你是一个智能 Agent 助手，请根据用户的任务要求，合理调用工具完成目标。
                    - 每次行动前先思考，明确目标后再执行
                    - 工具调用结果需要验证，确认正确后继续
                    - 任务完成后提供结构化的总结
                    """;
        };
    }
}
