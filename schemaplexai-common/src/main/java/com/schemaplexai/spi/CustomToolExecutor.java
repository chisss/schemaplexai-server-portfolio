package com.schemaplexai.spi;

import com.schemaplexai.common.model.ToolResult;

import java.util.Map;

/**
 * 自定义工具执行器 SPI 接口
 * <p>
 * 用户通过此接口实现自定义工具，系统通过 SPI 机制动态加载。
 * </p>
 * <p>
 * 使用方式：
 * <ol>
 *   <li>实现此接口，编写业务逻辑</li>
 *   <li>在 META-INF/services/com.schemaplexai.spi.CustomToolExecutor 注册</li>
 *   <li>将 JAR 包放入 classpath 或指定目录</li>
 * </ol>
 * </p>
 */
public interface CustomToolExecutor {

    /**
     * 获取工具代码（唯一标识）
     */
    String getToolCode();

    /**
     * 获取工具名称（用户友好）
     */
    String getName();

    /**
     * 获取工具描述
     */
    String getDescription();

    /**
     * 获取输入参数 Schema（JSON Schema 格式）
     * <p>
     * 用于前端表单渲染和参数校验
     * </p>
     *
     * @return JSON Schema 对象
     */
    default Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of()
        );
    }

    /**
     * 执行工具
     *
     * @param tenantId  租户 ID
     * @param agentId  Agent ID
     * @param context  执行上下文（包含配置等信息）
     * @param args     调用参数
     * @return 执行结果
     */
    ToolResult execute(String tenantId, String agentId, Map<String, Object> context, Map<String, Object> args);

    /**
     * 执行前的校验（可选实现）
     *
     * @param tenantId 租户 ID
     * @param config   工具配置
     * @return 校验通过返回 null，否则返回错误信息
     */
    default String validate(String tenantId, Map<String, Object> config) {
        return null;
    }

    /**
     * 获取工具类型
     */
    default String getType() {
        return "custom";
    }
}
