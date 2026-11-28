package com.schemaplexai.service.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.enums.AmendmentMatchTypeEnum;
import com.schemaplexai.common.enums.AmendmentScopeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.ToolApprovalAmendmentMapper;
import com.schemaplexai.model.entity.ToolApprovalAmendment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 工具审批修正服务（渐进式信任）
 * <p>参考 Codex CLI 的 Amendment 机制：用户批准一次工具执行后，
 * 系统自动学习并生成规则，后续相同模式的调用自动通过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolApprovalAmendmentService {

    private static final int MAX_USES = 20;

    private final ToolApprovalAmendmentMapper amendmentMapper;

    /**
     * 检查命令是否匹配已有的修正规则（自动放行）
     */
    public boolean isAutoApproved(String tenantId, String agentId, String toolCode, String command) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(toolCode)) {
            return false;
        }
        List<ToolApprovalAmendment> amendments = findActiveAmendments(tenantId, agentId, toolCode);
        for (ToolApprovalAmendment amendment : amendments) {
            if (matchesPattern(amendment, command) && tryConsume(amendment.getId(), tenantId)) {
                log.debug("工具调用自动审批: toolCode={}, amendmentId={}", toolCode, amendment.getId());
                return true;
            }
        }
        return false;
    }

    /**
     * 用户批准后创建修正规则
     */
    public ToolApprovalAmendment createAmendment(String tenantId, String agentId, String toolCode,
                                                  String command, String approvedBy,
                                                  AmendmentScopeEnum scope, LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime limit = now.plusDays(1);
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "审批修正规则的有效期必须晚于当前时间");
        }
        ToolApprovalAmendment amendment = new ToolApprovalAmendment();
        amendment.setTenantId(tenantId);
        amendment.setAgentId(AmendmentScopeEnum.AGENT.equals(scope) ? agentId : null);
        amendment.setToolCode(toolCode);
        amendment.setCommandPattern(command == null ? "" : command.strip());
        amendment.setMatchType(AmendmentMatchTypeEnum.EXACT.getCode());
        amendment.setScope(scope.getCode());
        amendment.setApprovedBy(approvedBy);
        amendment.setExpiresAt(expiresAt == null || expiresAt.isAfter(limit) ? limit : expiresAt);
        amendment.setMaxUses(MAX_USES);
        amendment.setUseCount(0);
        amendmentMapper.insert(amendment);
        log.info("创建审批修正规则: toolCode={}, amendmentId={}, scope={}", toolCode, amendment.getId(), scope.getCode());
        return amendment;
    }

    /**
     * 查询Agent的修正规则列表
     */
    public List<ToolApprovalAmendment> listAmendments(String tenantId, String agentId) {
        return amendmentMapper.selectList(new LambdaQueryWrapper<ToolApprovalAmendment>()
                .eq(ToolApprovalAmendment::getTenantId, tenantId)
                .and(w -> w.eq(ToolApprovalAmendment::getAgentId, agentId)
                        .or().eq(ToolApprovalAmendment::getScope, AmendmentScopeEnum.TENANT.getCode()))
                .orderByDesc(ToolApprovalAmendment::getCreatedAt));
    }

    /**
     * 删除修正规则
     */
    public void deleteAmendment(String tenantId, String id) {
        amendmentMapper.delete(new LambdaQueryWrapper<ToolApprovalAmendment>()
                .eq(ToolApprovalAmendment::getTenantId, tenantId)
                .eq(ToolApprovalAmendment::getId, id));
    }

    private List<ToolApprovalAmendment> findActiveAmendments(String tenantId, String agentId, String toolCode) {
        return amendmentMapper.selectList(new LambdaQueryWrapper<ToolApprovalAmendment>()
                .eq(ToolApprovalAmendment::getTenantId, tenantId)
                .eq(ToolApprovalAmendment::getToolCode, toolCode)
                .and(w -> w.eq(ToolApprovalAmendment::getAgentId, agentId)
                        .or().eq(ToolApprovalAmendment::getScope, AmendmentScopeEnum.TENANT.getCode()))
                .gt(ToolApprovalAmendment::getExpiresAt, LocalDateTime.now())
                .apply("COALESCE(use_count, 0) < max_uses"));
    }

    private boolean matchesPattern(ToolApprovalAmendment amendment, String command) {
        if (!StringUtils.hasText(command) || !StringUtils.hasText(amendment.getCommandPattern())) {
            return false;
        }
        String matchType = amendment.getMatchType();
        String pattern = amendment.getCommandPattern();
        if (AmendmentMatchTypeEnum.EXACT.getCode().equals(matchType)) {
            return command.equals(pattern);
        }
        if (AmendmentMatchTypeEnum.REGEX.getCode().equals(matchType)) {
            try {
                return Pattern.matches(pattern, command);
            } catch (Exception e) {
                log.warn("修正规则正则匹配失败: pattern={}, error={}", pattern, e.getMessage());
                return false;
            }
        }
        // 默认前缀匹配
        return command.startsWith(pattern);
    }

    private boolean tryConsume(String id, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        return amendmentMapper.update(null, new LambdaUpdateWrapper<ToolApprovalAmendment>()
                .eq(ToolApprovalAmendment::getId, id)
                .eq(ToolApprovalAmendment::getTenantId, tenantId)
                .gt(ToolApprovalAmendment::getExpiresAt, now)
                .apply("COALESCE(use_count, 0) < max_uses")
                .setSql("use_count = COALESCE(use_count, 0) + 1")
                .set(ToolApprovalAmendment::getLastUsedAt, now)) == 1;
    }
}
