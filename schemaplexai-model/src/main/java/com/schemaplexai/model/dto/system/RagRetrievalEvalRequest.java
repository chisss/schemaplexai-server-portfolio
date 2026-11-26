package com.schemaplexai.model.dto.system;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 检索评测请求（黄金集 + 期望文档）。
 *
 * <p>只要求标注"期望命中的文档 ID"，不要求标注具体 chunk：
 * 文档级标注成本低、可维护，且 Recall@K 在文档粒度上已经能反映粗召回是否可用。
 */
@Data
public class RagRetrievalEvalRequest {

    /** 项目/上下文范围；为空表示租户全量 */
    private List<String> projectIds;

    /** 截断位，为空取租户配置的 evalRecallK */
    private Integer k;

    @NotEmpty(message = "评测集不能为空")
    @Valid
    private List<EvalCase> cases;

    @Data
    public static class EvalCase {

        @NotBlank(message = "查询不能为空")
        private String query;

        @NotEmpty(message = "必须标注期望命中的文档 ID")
        private List<String> expectedDocIds;
    }
}
