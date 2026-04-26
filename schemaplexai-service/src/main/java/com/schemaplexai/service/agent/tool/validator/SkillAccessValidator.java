package com.schemaplexai.service.agent.tool.validator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.SkillStatusEnum;
import com.schemaplexai.dao.mapper.SkillInstallationMapper;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.model.entity.SkillInstallation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Skill 租户访问校验器
 */
@Component
@RequiredArgsConstructor
public class SkillAccessValidator {

    public static final String STATUS_OWNED = "owned";
    public static final String STATUS_INSTALLED = "installed";
    public static final String STATUS_ACTIVE_INSTALLATION = "active";
    public static final String STATUS_INACTIVE_SKILL = "inactive_skill";
    public static final String STATUS_NOT_INSTALLED = "not_installed";

    private static final List<String> VALID_INSTALLATION_STATUSES = List.of(STATUS_INSTALLED, STATUS_ACTIVE_INSTALLATION);

    private final SkillInstallationMapper skillInstallationMapper;

    public SkillAccessResult validate(String tenantId, Skill skill) {
        if (skill == null) {
            return SkillAccessResult.denied("missing", "Skill 不存在");
        }
        if (!SkillStatusEnum.ACTIVE.getCode().equalsIgnoreCase(skill.getStatus())) {
            return SkillAccessResult.denied(STATUS_INACTIVE_SKILL, "Skill 未启用");
        }
        if (StringUtils.hasText(skill.getTenantId()) && skill.getTenantId().equals(tenantId)) {
            return SkillAccessResult.allowed(STATUS_OWNED);
        }
        Long installedCount = skillInstallationMapper.selectCount(new LambdaQueryWrapper<SkillInstallation>()
                .eq(SkillInstallation::getTenantId, tenantId)
                .eq(SkillInstallation::getSkillId, skill.getId())
                .in(SkillInstallation::getStatus, VALID_INSTALLATION_STATUSES));
        if (installedCount != null && installedCount > 0) {
            return SkillAccessResult.allowed(STATUS_INSTALLED);
        }
        return SkillAccessResult.denied(STATUS_NOT_INSTALLED, "Skill 未安装或无访问权限");
    }
}
