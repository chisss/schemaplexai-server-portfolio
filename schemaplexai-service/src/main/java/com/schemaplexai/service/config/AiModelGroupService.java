package com.schemaplexai.service.config;

import com.schemaplexai.model.dto.system.AiModelGroupAutoGenerateRequest;
import com.schemaplexai.model.dto.system.AiModelGroupCreateRequest;
import com.schemaplexai.model.dto.system.AiModelGroupItemSaveRequest;
import com.schemaplexai.model.dto.system.AiModelGroupUpdateRequest;
import com.schemaplexai.model.vo.system.AiModelGroupVO;

import java.util.List;

/**
 * AI模型组管理服务接口
 */
public interface AiModelGroupService {

    List<AiModelGroupVO> listByCurrentTenant();

    AiModelGroupVO getById(String id);

    AiModelGroupVO create(AiModelGroupCreateRequest request);

    AiModelGroupVO update(String id, AiModelGroupUpdateRequest request);

    void delete(String id);

    /** 整体替换模型组成员排序，modelIds列表顺序即优先级顺序 */
    AiModelGroupVO saveItems(String groupId, AiModelGroupItemSaveRequest request);

    /** 按策略自动生成模型组 */
    AiModelGroupVO autoGenerate(AiModelGroupAutoGenerateRequest request);
}
