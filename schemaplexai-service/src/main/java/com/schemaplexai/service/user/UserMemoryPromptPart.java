package com.schemaplexai.service.user;

/**
 * 用户记忆 Prompt 片段
 */
public record UserMemoryPromptPart(String staticPrompt,
                                   String contextualPrompt,
                                   int staticCount,
                                   int contextualCount) {
}
