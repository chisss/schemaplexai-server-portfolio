package com.schemaplexai.service.tool.registry;

import com.schemaplexai.common.enums.CustomToolTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.tool.executor.ToolExecutor;
import com.schemaplexai.service.tool.loader.CustomToolLoader;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.service.tool.config.ToolConfigInjector;
import com.schemaplexai.service.tool.security.ToolSecurityValidator;
import com.schemaplexai.spi.CustomToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义工具注册表
 * <p>
 * 管理所有自定义工具的运行时注册和执行路由。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomToolRegistry {

    private final CustomToolLoader toolLoader;
    private final ToolConfigInjector configInjector;
    private final ToolSecurityValidator securityValidator;
    private final AgentToolBindingMapper agentToolBindingMapper;

    /** 本地注册表 */
    private final Map<String, CustomToolExecutor> localRegistry = new ConcurrentHashMap<>();

    /** 注册自定义执行器
     *
     * @param executor 执行器实例
     */
    public void register(CustomToolExecutor executor) {
        if (executor == null || !StringUtils.hasText(executor.getToolCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无效的执行器");
        }
        localRegistry.put(executor.getToolCode(), executor);
        log.info("注册自定义工具: toolCode={}", executor.getToolCode());
    }

    /**
     * 注销自定义执行器
     */
    public void unregister(String toolCode) {
        if (localRegistry.remove(toolCode) != null) {
            log.info("注销自定义工具: toolCode={}", toolCode);
        }
        toolLoader.unregister(toolCode);
    }

    /**
     * 获取执行器
     */
    public CustomToolExecutor getExecutor(String toolCode) {
        // 先查本地注册表
        CustomToolExecutor executor = localRegistry.get(toolCode);
        if (executor != null) {
            return executor;
        }

        // 再查加载器
        return toolLoader.getExecutor(toolCode);
    }

    /**
     * 执行自定义工具
     */
    public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall) {
        if (binding == null || !StringUtils.hasText(binding.getToolCode())) {
            return failure(toolCall, "工具绑定无效");
        }

        String toolCode = binding.getToolCode();
        CustomToolExecutor executor = getExecutor(toolCode);
        if (executor == null) {
            return failure(toolCall, "未找到工具执行器: " + toolCode);
        }

        try {
            // 安全校验
            securityValidator.validateTenantIsolation(tenantId, binding.getTenantId());

            // 加载配置
            Map<String, Object> config = configInjector.inject(tenantId, binding);

            // 校验配置
            String validationError = executor.validate(tenantId, config);
            if (validationError != null) {
                return failure(toolCall, "配置校验失败: " + validationError);
            }

            // 提取参数
            Map<String, Object> args = extractArguments(toolCall);

            // 执行
            Object result = executor.execute(tenantId, agentId, config, args);

            return success(toolCall, result);
        } catch (BusinessException e) {
            log.warn("自定义工具执行业务异常: toolCode={}, error={}", toolCode, e.getMessage());
            return failure(toolCall, e.getMessage());
        } catch (Exception e) {
            log.error("自定义工具执行异常: toolCode={}", toolCode, e);
            return failure(toolCall, "执行异常: " + e.getMessage());
        }
    }

    /**
     * HTTP 类型工具执行
     */
    public ToolResult executeHttp(String tenantId, String agentId, AgentToolBinding binding,
                                  ToolCall toolCall, String url, Map<String, Object> headers, Map<String, Object> args) {
        try {
            // 校验 URL（SSRF 防护）
            securityValidator.validateUrl(url);

            // TODO: 实现 HTTP 调用逻辑
            // 这里可以调用 RestTemplate 或 WebClient

            return success(toolCall, Map.of("status", "not_implemented", "message", "HTTP 执行待实现"));
        } catch (Exception e) {
            log.error("HTTP 工具执行异常: {}", e.getMessage());
            return failure(toolCall, "HTTP 执行失败: " + e.getMessage());
        }
    }

    /**
     * 脚本类型工具执行
     */
    public ToolResult executeScript(String tenantId, String agentId, AgentToolBinding binding,
                                   ToolCall toolCall, CustomToolTypeEnum scriptType, String script) {
        try {
            Object result = toolLoader.executeScript(scriptType, script, extractArguments(toolCall));
            return success(toolCall, result);
        } catch (Exception e) {
            log.error("脚本执行异常: toolCode={}", binding.getToolCode(), e);
            return failure(toolCall, "脚本执行失败: " + e.getMessage());
        }
    }

    // ==================== 私有方法 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractArguments(ToolCall toolCall) {
        if (toolCall == null || toolCall.getArguments() == null) {
            return Map.of();
        }
        if (toolCall.getArguments() instanceof Map) {
            return (Map<String, Object>) toolCall.getArguments();
        }
        return Map.of();
    }

    private ToolResult success(ToolCall toolCall, Object payload) {
        return ToolResult.builder()
                .callId(toolCall != null ? toolCall.getCallId() : null)
                .toolCode(toolCall != null ? toolCall.getToolCode() : null)
                .success(true)
                .result(toolCall != null && toolCall.getArguments() != null
                        ? toolCall.getArguments().deepCopy() : null)
                .build();
    }

    private ToolResult failure(ToolCall toolCall, String message) {
        return ToolResult.builder()
                .callId(toolCall != null ? toolCall.getCallId() : null)
                .toolCode(toolCall != null ? toolCall.getToolCode() : null)
                .success(false)
                .errorMessage(message)
                .build();
    }
}
