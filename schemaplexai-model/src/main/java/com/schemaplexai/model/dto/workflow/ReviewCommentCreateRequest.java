package com.schemaplexai.model.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建评审意见请求
 */
@Data
public class ReviewCommentCreateRequest {

    @NotBlank(message = "意见级别不能为空")
    private String level;

    /** 分类: 功能完整性/技术可行性/性能/安全/其他 */
    private String category;

    @NotBlank(message = "评审内容不能为空")
    private String content;

    /** 文档位置 */
    private String location;
}
