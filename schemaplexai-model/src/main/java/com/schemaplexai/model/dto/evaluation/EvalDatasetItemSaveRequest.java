package com.schemaplexai.model.dto.evaluation;

import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;

/**
 * 保存评估数据集条目请求
 */
@Data
public class EvalDatasetItemSaveRequest {

    @Valid
    private List<EvalDatasetItemRequest> items;
}
