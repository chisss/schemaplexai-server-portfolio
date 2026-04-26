package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Agent 执行请求 DTO
 */
@Data
public class AgentExecuteDTO {

    /** 执行任务描述/指令 */
    @NotBlank(message = "执行指令不能为空")
    private String prompt;

    /** 附加上下文数据 */
    private Map<String, Object> context;

    /** 指定执行模型（为空时使用 Agent 默认模型） */
    private String model;

    /** 是否流式输出 */
    private Boolean stream = false;

    /** 会话标识（传入已有 conversationId 可续接多轮对话，为空则自动生成新会话） */
    private String conversationId;

    /** 附件对象 ID 列表（上传后返回的 fileId） */
    private List<String> attachmentIds;

    /** 推理强度：low / medium / high */
    private String reasoningStrength;

    /** 直接指定技能编码，前端快捷技能调用时透传 */
    private String skillCode;

    /** 期望输出格式：markdown / plain_text / structured_json */
    private String outputFormat;

    /** 执行模式: auto / plan / suggest（默认 auto） */
    private String executionMode;
}
