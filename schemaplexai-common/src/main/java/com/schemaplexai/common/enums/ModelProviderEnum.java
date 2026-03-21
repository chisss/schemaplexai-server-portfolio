package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author wei.sun
 * @enum
 * @since 2026/3/17 13:37
 */
@Getter
@AllArgsConstructor
public enum ModelProviderEnum {

    OPENAI("openai", "OpenAI"),
    HUGGINGFACE("huggingface", "HuggingFace"),
    ZHIHU("zhihu", "Zhihu"),
    BAIDU("baidu", "Baidu"),
    TENCENT("tencent", "Tencent"),
    ALIBABA("alibaba", "Alibaba"),
    YANDEX("yandex", "Yandex"),
    GOOGLE("google", "Google"),
    AZURE("azure", "Azure"),
    COHERE("cohere", "Cohere"),
    ANTHROPIC("anthropic", "Anthropic"),
    GEMINI("gemini", "Gemini"),
    CODEX("codex", "Codex"),
    CLAUDE("claude", "Claude"),
    ZHUPU("glm", "ZhuPu"),
    MINIMAX("minimax", "Minimax"),
    DEEPSEEK("deepseek", "DeepSeek"),
    CHATGPT("chatgpt", "ChatGPT");

    private final String code;
    private final String description;

}
