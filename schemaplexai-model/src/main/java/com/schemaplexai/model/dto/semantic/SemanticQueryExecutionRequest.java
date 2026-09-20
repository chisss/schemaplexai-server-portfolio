package com.schemaplexai.model.dto.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 语义查询计划操作请求；客户端只能提交自然语言和计划 hash。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SemanticQueryExecutionRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 2000, message = "问题不能超过2000个字符") String question,
        @NotBlank(message = "语义版本不能为空")
        @Size(max = 100, message = "语义版本标识不能超过100个字符") String semanticVersionId,
        @NotBlank(message = "数据源不能为空")
        @Size(max = 100, message = "数据源标识不能超过100个字符") String sourceId,
        @Size(max = 1000, message = "上下文不能超过1000个字符") String context,
        @Size(max = 128, message = "计划 hash 不能超过128个字符") String expectedPlanHash,
        @Min(value = 1, message = "结果行数上限必须大于0")
        @Max(value = 5000, message = "结果行数上限不能超过5000") Integer maxRows,
        @Min(value = 1, message = "超时时间必须大于0")
        @Max(value = 300, message = "超时时间不能超过300秒") Integer timeoutSeconds) {
}
