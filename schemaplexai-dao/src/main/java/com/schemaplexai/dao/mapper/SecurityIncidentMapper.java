package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.SecurityIncident;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.util.StringUtils;

/**
 * 安全风控事件 Mapper
 */
@Mapper
public interface SecurityIncidentMapper extends BaseMapper<SecurityIncident> {

    /** 查找当前暂停对应的安全事件，排除上次恢复前的历史事件。 */
    default SecurityIncident findExecutionPause(AgentExecution execution) {
        boolean started = execution.getStartedAt() != null;
        var since = started ? execution.getStartedAt() : execution.getCreatedAt();
        if (!StringUtils.hasText(execution.getErrorMessage()) || since == null) {
            return null;
        }
        return selectOne(new LambdaQueryWrapper<SecurityIncident>()
                .eq(SecurityIncident::getTenantId, execution.getTenantId())
                .eq(SecurityIncident::getSourceType, SecurityComplianceConstant.RESOURCE_TYPE_AGENT_EXECUTION)
                .eq(SecurityIncident::getSourceId, execution.getId())
                .eq(SecurityIncident::getDecision, SecurityComplianceConstant.DECISION_PAUSE)
                .eq(started, SecurityIncident::getEventTitle, execution.getErrorMessage())
                .ge(SecurityIncident::getCreatedAt, since)
                .orderByDesc(SecurityIncident::getCreatedAt)
                .orderByDesc(SecurityIncident::getIncidentNo)
                .last("LIMIT 1"));
    }
}
