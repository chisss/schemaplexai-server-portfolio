package com.schemaplexai.service.tool.config;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 工具配置注入器
 */
@Component
@RequiredArgsConstructor
public class ToolConfigInjector {

    private final ToolConfigService toolConfigService;
    private final AgentToolBindingMapper agentToolBindingMapper;

    /**
     * 根据 bindingId 注入配置
     */
    public Map<String, Object> inject(String tenantId, String bindingId, String toolCode) {
        if (!StringUtils.hasText(bindingId)) {
            return Map.of();
        }
        AgentToolBinding binding = agentToolBindingMapper.selectById(bindingId);
        if (binding == null) {
            throw new BusinessException(ResultCode.CONFIG_NOT_FOUND, "工具绑定不存在");
        }
        if (StringUtils.hasText(toolCode) && !toolCode.trim().equalsIgnoreCase(binding.getToolCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "工具编码与绑定不一致");
        }
        return inject(tenantId, binding);
    }

    /**
     * 根据 binding 注入配置
     */
    public Map<String, Object> inject(String tenantId, AgentToolBinding binding) {
        if (binding == null) {
            return Map.of();
        }
        return toolConfigService.resolveEffectiveConfig(tenantId, binding);
    }
}
