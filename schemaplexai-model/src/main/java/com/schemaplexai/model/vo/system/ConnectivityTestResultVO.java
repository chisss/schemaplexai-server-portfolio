package com.schemaplexai.model.vo.system;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 模型连通性测试结果 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectivityTestResultVO {

    /** 测试状态: success/failed/timeout */
    private String status;

    /** 响应延迟（毫秒） */
    private Long latencyMs;

    /** 模型返回的实际模型名称 */
    private String modelName;

    /** 输入 Token 数 */
    private Integer tokenInput;

    /** 输出 Token 数 */
    private Integer tokenOutput;

    /** 错误码（失败时） */
    private String errorCode;

    /** 错误信息（失败时） */
    private String errorMessage;

    /** 测试时间 */
    private LocalDateTime testedAt;
}
