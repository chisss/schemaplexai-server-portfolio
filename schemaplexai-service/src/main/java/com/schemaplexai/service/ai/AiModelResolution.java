package com.schemaplexai.service.ai;

/**
 * AI 模型解析结果：{@link AIModelRouter#resolveByModelName} 的返回值
 *
 * <p>将路由后的 Provider 和运行时连接配置封装在一起，Engine 无需分两步查询。
 *
 * @param provider 路由到的 AIProvider 实例（Claude / OpenAI / Gemini）
 * @param config   从 sf_ai_model 表加载的运行时连接配置
 */
public record AiModelResolution(AIProvider provider, AiModelConfig config) {
}
