package com.schemaplexai.service.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.enums.AmendmentMatchTypeEnum;
import com.schemaplexai.common.enums.AmendmentScopeEnum;
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
            if (matchesPattern(amendment, command)) {
                incrementUseCount(amendment.getId());
                log.debug("工具调用自动放行: toolCode={}, command={}, amendmentId={}", toolCode, command, amendment.getId());
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
        String pattern = extractCommandPattern(toolCode, command);
        ToolApprovalAmendment amendment = new ToolApprovalAmendment();
        amendment.setTenantId(tenantId);
        amendment.setAgentId(AmendmentScopeEnum.AGENT.equals(scope) ? agentId : null);
        amendment.setToolCode(toolCode);
        amendment.setCommandPattern(pattern);
        amendment.setMatchType(AmendmentMatchTypeEnum.PREFIX.getCode());
        amendment.setScope(scope.getCode());
        amendment.setApprovedBy(approvedBy);
        amendment.setExpiresAt(expiresAt);
        amendment.setUseCount(0);
        amendmentMapper.insert(amendment);
        log.info("创建审批修正规则: toolCode={}, pattern={}, scope={}", toolCode, pattern, scope.getCode());
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
    public void deleteAmendment(String id) {
        amendmentMapper.deleteById(id);
    }

    private List<ToolApprovalAmendment> findActiveAmendments(String tenantId, String agentId, String toolCode) {
        return amendmentMapper.selectList(new LambdaQueryWrapper<ToolApprovalAmendment>()
                .eq(ToolApprovalAmendment::getTenantId, tenantId)
                .eq(ToolApprovalAmendment::getToolCode, toolCode)
                .and(w -> w.eq(ToolApprovalAmendment::getAgentId, agentId)
                        .or().eq(ToolApprovalAmendment::getScope, AmendmentScopeEnum.TENANT.getCode()))
                .and(w -> w.isNull(ToolApprovalAmendment::getExpiresAt)
                        .or().gt(ToolApprovalAmendment::getExpiresAt, LocalDateTime.now())));
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

    private void incrementUseCount(String id) {
        amendmentMapper.update(null, new LambdaUpdateWrapper<ToolApprovalAmendment>()
                .eq(ToolApprovalAmendment::getId, id)
                .setSql("use_count = use_count + 1")
                .set(ToolApprovalAmendment::getLastUsedAt, LocalDateTime.now()));
    }

    /**
     * 从命令中提取模式（取基础命令部分作为前缀）
     */
    private String extractCommandPattern(String toolCode, String command) {
        if ("sys.bash".equals(toolCode) && StringUtils.hasText(command)) {
            // 提取第一个命令词 + 第一个参数作为前缀
            String[] parts = command.trim().split("\\s+", 3);
            if (parts.length >= 2) {
                return parts[0] + " " + parts[1];
            }
            return parts[0];
        }
        return command != null ? command.trim() : "";
    }
}