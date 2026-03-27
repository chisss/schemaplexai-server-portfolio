package com.schemaplexai.model.vo.agent;

import lombok.Data;

/**
 * 可用工具 VO（用于前端下拉选择）
 */
@Data
public class AvailableToolVO {

    /** 工具代码 */
    private String toolCode;

    /** 工具显示名称 */
    private String name;

    /** 来源类型: builtin / skill / mcp */
    private String sourceType;

    /** 来源ID: MCP Server ID / Skill ID（builtin 时为空） */
    private String sourceRefId;

    /** 工具描述 */
    private String description;

    /** 输入参数 JSON Schema */
    private String inputSchema;

    /** 支持的操作系统 */
    private String osSupport;

    /** 是否已被当前 Agent 绑定 */
    private Boolean alreadyBound;
}
