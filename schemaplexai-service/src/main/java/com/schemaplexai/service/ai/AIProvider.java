package com.schemaplexai.service.ai;

import java.util.List;

/**
 * AI 模型 Provider 接口 — 各模型适配器的统一抽象（策略模式）
 *
 * <p>实现类只需关注协议差异（请求格式/响应解析），连接参数统一由调用方通过
 * {@link AiModelConfig} 传入，Provider 不持有任何静态配置。
 */
public interface AIProvider {

    /**
     * 获取 Provider 名称（如 "claude"、"openai"、"gemini"）
     */
    String getName();

    /**
     * 多轮对话请求（Agentic Loop 主入口）
     *
     * @param systemPrompt 系统提示词（可为 null）
     * @param messages     对话历史（user/assistant 交替）
     * @param config       运行时模型连接配置（从 sf_ai_model 表动态加载）
     * @return {@link ChatResponse}
     */
    ChatResponse chatWithHistory(String systemPrompt, List<ChatMessage> messages, AiModelConfig config);
}
