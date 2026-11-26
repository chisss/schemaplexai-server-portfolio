package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 回答接地校验请求。
 *
 * <p>典型调用方：质量中心、人工复核台、以及"回答生成后的可选校验"。
 * contextChunks 的顺序必须与回答里 [n] 编号一致，否则越界判定会误报。
 */
@Data
public class RagGroundingCheckRequest {

    @NotBlank(message = "回答内容不能为空")
    private String answer;

    /** 实际进入上下文的片段，顺序对应 [1][2]… */
    private List<String> contextChunks;
}
