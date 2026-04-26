package com.schemaplexai.service.agent.tool.validator;

/**
 * Skill 访问校验结果
 */
public record SkillAccessResult(boolean accessible,
                                String status,
                                String message) {

    public static SkillAccessResult allowed(String status) {
        return new SkillAccessResult(true, status, null);
    }

    public static SkillAccessResult denied(String status, String message) {
        return new SkillAccessResult(false, status, message);
    }
}
