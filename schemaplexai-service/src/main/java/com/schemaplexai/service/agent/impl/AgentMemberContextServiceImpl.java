package com.schemaplexai.service.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.AgentContextBindingSourceEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberContextBindingMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberMapper;
import com.schemaplexai.dao.mapper.ContextEntityMapper;
import com.schemaplexai.model.dto.agent.AgentTeamMemberContextBindingDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentTeamMember;
import com.schemaplexai.model.entity.AgentTeamMemberContextBinding;
import com.schemaplexai.model.entity.ContextEntity;
import com.schemaplexai.model.vo.agent.AgentTeamMemberContextBindingVO;
import com.schemaplexai.service.agent.AgentMemberContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Team 成员上下文绑定服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemberContextServiceImpl implements AgentMemberContextService {

    private final AgentMapper agentMapper;
    private final AgentTeamMemberMapper agentTeamMemberMapper;
    private final AgentTeamMemberContextBindingMapper agentTeamMemberContextBindingMapper;
    private final ContextEntityMapper contextEntityMapper;

    @Override
    public List<AgentTeamMemberContextBindingVO> getMemberContextBindings(String memberId) {
        requireMemberExists(memberId);
        return agentTeamMemberContextBindingMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMemberContextBinding>()
                        .eq(AgentTeamMemberContextBinding::getMemberId, memberId)
                        .orderByAsc(AgentTeamMemberContextBinding::getSortOrder)
                        .orderByAsc(AgentTeamMemberContextBinding::getCreatedAt))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AgentTeamMemberContextBindingVO> saveMemberContextBindings(String memberId, List<AgentTeamMemberContextBindingDTO> request) {
        requireMemberExists(memberId);
        agentTeamMemberContextBindingMapper.delete(
                new LambdaQueryWrapper<AgentTeamMemberContextBinding>()
                        .eq(AgentTeamMemberContextBinding::getMemberId, memberId));

        if (!CollectionUtils.isEmpty(request)) {
            int insertedCount = 0;
            for (int i = 0; i < request.size(); i++) {
                AgentTeamMemberContextBindingDTO item = request.get(i);
                if (item == null) {
                    continue;
                }
                if (AgentContextBindingSourceEnum.CONTEXT_MODULE.getCode().equalsIgnoreCase(item.getSourceType())
                        && !StringUtils.hasText(item.getContextId())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "上下文模块绑定必须指定 contextId");
                }

                AgentTeamMemberContextBinding binding = new AgentTeamMemberContextBinding();
                binding.setMemberId(memberId);
                binding.setContextId(item.getContextId());
                binding.setSourceType(item.getSourceType());
                binding.setSourceConfig(item.getSourceConfig());
                binding.setContent(item.getContent());
                binding.setTitle(item.getTitle());
                binding.setStatus("active");
                binding.setSortOrder(item.getSortOrder() != null ? item.getSortOrder() : i);
                agentTeamMemberContextBindingMapper.insert(binding);
                insertedCount++;
            }
            log.info("保存成员上下文绑定成功: memberId={}, count={}", memberId, insertedCount);
        }
        return getMemberContextBindings(memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMemberContextBinding(String bindingId) {
        AgentTeamMemberContextBinding binding = agentTeamMemberContextBindingMapper.selectById(bindingId);
        if (binding == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "成员上下文绑定不存在");
        }
        String currentTenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(currentTenantId)) {
            AgentTeamMember member = agentTeamMemberMapper.selectById(binding.getMemberId());
            if (member != null) {
                Agent agent = agentMapper.selectById(member.getAgentId());
                if (agent == null || !currentTenantId.equals(agent.getTenantId())) {
                    throw new BusinessException(ResultCode.FORBIDDEN, "无权删除该上下文绑定");
                }
            }
        }
        agentTeamMemberContextBindingMapper.deleteById(bindingId);
        log.info("删除成员上下文绑定成功: bindingId={}", bindingId);
    }

    private void requireMemberExists(String memberId) {
        if (!StringUtils.hasText(memberId) || agentTeamMemberMapper.selectById(memberId) == null) {
            throw new BusinessException(ResultCode.AGENT_TEAM_MEMBER_NOT_FOUND);
        }
    }

    private AgentTeamMemberContextBindingVO toVO(AgentTeamMemberContextBinding binding) {
        AgentTeamMemberContextBindingVO vo = new AgentTeamMemberContextBindingVO();
        vo.setId(binding.getId());
        vo.setMemberId(binding.getMemberId());
        vo.setContextId(binding.getContextId());
        vo.setSourceType(binding.getSourceType());
        vo.setSourceConfig(binding.getSourceConfig());
        vo.setContent(binding.getContent());
        vo.setTitle(binding.getTitle());
        vo.setStatus(binding.getStatus());
        vo.setSortOrder(binding.getSortOrder());
        vo.setCreatedAt(binding.getCreatedAt());
        if (StringUtils.hasText(binding.getContextId())) {
            ContextEntity context = contextEntityMapper.selectById(binding.getContextId());
            if (context != null) {
                vo.setContextName(context.getName());
            }
        }
        return vo;
    }
}
