package com.schemaplexai.service.agent.tool.validator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.SkillInstallationMapper;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.model.entity.SkillInstallation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillAccessValidatorTest {

    private final SkillInstallationMapper installationMapper = mock(SkillInstallationMapper.class);
    private final SkillAccessValidator validator = new SkillAccessValidator(installationMapper);

    @Test
    void shouldAllowTenantOwnedActiveSkill() {
        Skill skill = activeSkill("skill-1", "tenant-1");

        SkillAccessResult result = validator.validate("tenant-1", skill);

        assertThat(result.accessible()).isTrue();
        assertThat(result.status()).isEqualTo(SkillAccessValidator.STATUS_OWNED);
    }

    @Test
    void shouldAllowActiveInstallationStatus() {
        Skill skill = activeSkill("skill-1", null);
        when(installationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        SkillAccessResult result = validator.validate("tenant-1", skill);

        assertThat(result.accessible()).isTrue();
        assertThat(result.status()).isEqualTo(SkillAccessValidator.STATUS_INSTALLED);
    }

    @Test
    void shouldDenyInactiveSkill() {
        Skill skill = activeSkill("skill-1", null);
        skill.setStatus("inactive");

        SkillAccessResult result = validator.validate("tenant-1", skill);

        assertThat(result.accessible()).isFalse();
        assertThat(result.status()).isEqualTo(SkillAccessValidator.STATUS_INACTIVE_SKILL);
    }

    @Test
    void shouldDenyMissingInstallation() {
        Skill skill = activeSkill("skill-1", null);
        when(installationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        SkillAccessResult result = validator.validate("tenant-1", skill);

        assertThat(result.accessible()).isFalse();
        assertThat(result.status()).isEqualTo(SkillAccessValidator.STATUS_NOT_INSTALLED);
    }

    private Skill activeSkill(String id, String tenantId) {
        Skill skill = new Skill();
        skill.setId(id);
        skill.setTenantId(tenantId);
        skill.setStatus("active");
        return skill;
    }
}
