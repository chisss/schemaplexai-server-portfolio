package com.schemaplexai.service.ai;

import dev.langchain4j.model.chat.ChatModel;

/**
 * LangChain4j 模型解析结果：{@link AIModelRouter#resolveByModelName} 的返回值
 *
 * <p>包含动态构建的 {@link ChatModel} 实例（由 {@link LangChain4jModelFactory} 缓存管理）
 * 和对应的运行时连接配置。
 *
 * @param model  LangChain4j ChatModel 实例
 * @param config 从 sf_ai_model 表加载的运行时连接配置
 */
public record LangChain4jResolution(ChatModel model, AiModelConfig config) {
}
