package com.schemaplexai.model.dto.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 自然语言语义解释请求，不接受原始查询语言。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SemanticQueryInterpretRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 2000, message = "问题不能超过2000个字符") String question,
        @Size(max = 100, message = "语义版本标识不能超过100个字符") String semanticVersionId,
        @Size(max = 100, message = "数据源标识不能超过100个字符") String sourceId,
        @Size(max = 1000, message = "上下文不能超过1000个字符") String context,
        @Size(max = 128, message = "数据集标识不能超过128个字符") String datasetId) {

    public SemanticQueryInterpretRequest(String question, String semanticVersionId, String sourceId, String context) {
        this(question, semanticVersionId, sourceId, context, null);
    }
}
