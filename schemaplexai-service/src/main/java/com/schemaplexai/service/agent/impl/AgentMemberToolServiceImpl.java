package com.schemaplexai.service.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberToolBindingMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.dto.agent.AgentTeamMemberToolBindingDTO;
import com.schemaplexai.model.entity.AgentTeamMemberToolBinding;
import com.schemaplexai.model.vo.agent.AgentTeamMemberToolBindingVO;
import com.schemaplexai.service.agent.AgentMemberToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 团队成员工具绑定服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemberToolServiceImpl implements AgentMemberToolService {

    private final AgentMapper agentMapper;
    private final AgentTeamMemberMapper agentTeamMemberMapper;
    private final AgentTeamMemberToolBindingMapper agentTeamMemberToolBindingMapper;

    @Override
    public List<AgentTeamMemberToolBindingVO> getMemberToolBindings(String memberId) {
        requireMemberExists(memberId);
        var bindings = agentTeamMemberToolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMemberToolBinding>()
                        .eq(AgentTeamMemberToolBinding::getMemberId, memberId)
                        .orderByAsc(AgentTeamMemberToolBinding::getPriority)
                        .orderByAsc(AgentTeamMemberToolBinding::getCreatedAt));
        return bindings.stream().map(this::toVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AgentTeamMemberToolBindingVO> saveMemberToolBindings(String memberId, List<AgentTeamMemberToolBindingDTO> request) {
        requireMemberExists(memberId);

        // 全量覆盖：先删后插
        agentTeamMemberToolBindingMapper.delete(
                new LambdaQueryWrapper<AgentTeamMemberToolBinding>()
                        .eq(AgentTeamMemberToolBinding::getMemberId, memberId));

        if (!CollectionUtils.isEmpty(request)) {
            Set<String> deduplicatedKeys = new HashSet<>();
            int insertedCount = 0;
            for (AgentTeamMemberToolBindingDTO item : request) {
                if (item == null || !StringUtils.hasText(item.getToolCode())) {
                    continue;
                }
                String toolCode = item.getToolCode().trim();
                String sourceType = StringUtils.hasText(item.getSourceType())
                        ? item.getSourceType().trim().toLowerCase() : "builtin";
                String sourceRefId = StringUtils.hasText(item.getSourceRefId()) ? item.getSourceRefId().trim() : null;

                if ("mcp".equals(sourceType) && !StringUtils.hasText(sourceRefId)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "MCP 工具必须指定 sourceRefId");
                }
                if ("skill".equals(sourceType) && !StringUtils.hasText(sourceRefId)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "Skill 工具必须指定 sourceRefId");
                }

                // 去重
                String uniqueKey = buildKey(sourceType, sourceRefId, toolCode);
                if (!deduplicatedKeys.add(uniqueKey)) {
                    continue;
                }

                var binding = new AgentTeamMemberToolBinding();
                binding.setMemberId(memberId);
                binding.setToolCode(toolCode);
                binding.setSourceType(sourceType);
                binding.setSourceRefId(sourceRefId);
                binding.setEnabled(item.getEnabled() == null || item.getEnabled());
                binding.setPriority(item.getPriority() == null ? 100 : item.getPriority());
                binding.setConfigOverride(item.getConfigOverride());
                agentTeamMemberToolBindingMapper.insert(binding);
                insertedCount++;
            }
            log.info("保存团队成员工具绑定成功: memberId={}, count={}", memberId, insertedCount);
        }
        return getMemberToolBindings(memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMemberToolBinding(String bindingId) {
        var binding = agentTeamMemberToolBindingMapper.selectById(bindingId);
        if (binding == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "成员工具绑定不存在");
        }
        // 租户隔离校验：binding → member → agent → tenantId
        String currentTenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(currentTenantId)) {
            var member = agentTeamMemberMapper.selectById(binding.getMemberId());
            if (member != null) {
                var agent = agentMapper.selectById(member.getAgentId());
                if (agent == null || !currentTenantId.equals(agent.getTenantId())) {
                    throw new BusinessException(ResultCode.FORBIDDEN, "无权删除该工具绑定");
                }
            }
        }
        agentTeamMemberToolBindingMapper.deleteById(bindingId);
        log.info("删除团队成员工具绑定成功: bindingId={}", bindingId);
    }

    private void requireMemberExists(String memberId) {
        if (!StringUtils.hasText(memberId) || agentTeamMemberMapper.selectById(memberId) == null) {
            throw new BusinessException(ResultCode.AGENT_TEAM_MEMBER_NOT_FOUND);
        }
    }

    private String buildKey(String sourceType, String sourceRefId, String toolCode) {
        String normalizedSourceType = StringUtils.hasText(sourceType) ? sourceType.trim().toLowerCase() : "builtin";
        String normalizedSourceRefId = StringUtils.hasText(sourceRefId) ? sourceRefId.trim() : "";
        String normalizedToolCode = StringUtils.hasText(toolCode) ? toolCode.trim().toLowerCase() : "";
        return normalizedSourceType + "|" + normalizedSourceRefId + "|" + normalizedToolCode;
    }

    private AgentTeamMemberToolBindingVO toVO(AgentTeamMemberToolBinding binding) {
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
}
