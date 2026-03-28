package com.schemaplexai.service.agent.tool;

/**
 * 工具执行器常量定义
 * 注意: 类型相关常量请使用对应的枚举类
 * @see com.schemaplexai.common.enums.SourceTypeEnum
 * @see com.schemaplexai.common.enums.SkillImplementationTypeEnum
 * @see com.schemaplexai.common.enums.BrainstormThemeEnum
 * @see com.schemaplexai.common.enums.BrainstormDirectionEnum
 * @see com.schemaplexai.common.enums.FrontendFrameworkEnum
 * @see com.schemaplexai.common.enums.CssStyleEnum
 */
public final class ToolConstants {

    private ToolConstants() {
    }

    // ==================== Builtin Skill Codes ====================
    /** 内置 Skill: Excel 生成器 */
    public static final String SKILL_CODE_EXCEL = "skill.excel";

    /** 内置 Skill: 思维导图生成器 */
    public static final String SKILL_CODE_BRAINSTORM = "skill.brainstorm";

    /** 内置 Skill: 前端设计生成器 */
    public static final String SKILL_CODE_FRONTEND_DESIGN = "skill.frontend-design";

    /** 内置 Skill: 代码审查器 */
    public static final String SKILL_CODE_CODE_REVIEW = "skill.code-review";

    /** 内置 Skill: API 文档生成器 */
    public static final String SKILL_CODE_API_DOC = "skill.api-doc";

    // ==================== Default Values ====================
    /** 默认 Excel 输出目录 */
    public static final String DEFAULT_EXCEL_OUTPUT_DIR = "/tmp";

    /** 默认 Excel 文件名 */
    public static final String DEFAULT_EXCEL_FILE_NAME = "output.xlsx";

    /** 默认工作表名称 */
    public static final String DEFAULT_SHEET_NAME = "Sheet1";

    /** 默认思维导图主题 */
    public static final String DEFAULT_BRAINSTORM_THEME = "default";

    /** 默认思维导图方向 */
    public static final String DEFAULT_BRAINSTORM_DIRECTION = "LR";

    /** 默认前端框架 */
    public static final String DEFAULT_FRAMEWORK = "react";

    /** 默认 CSS 样式 */
    public static final String DEFAULT_CSS_STYLE = "tailwind";
}
