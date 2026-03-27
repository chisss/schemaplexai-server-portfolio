package com.schemaplexai.model.dto.system;

import lombok.Data;

import java.util.List;

/**
 * 保存模型组成员排序请求DTO
 */
@Data
public class AiModelGroupItemSaveRequest {

    /** 按顺序排列的模型ID列表，第一个优先级最高 */
    private List<String> modelIds;
}
