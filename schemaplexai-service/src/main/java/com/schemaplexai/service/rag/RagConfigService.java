package com.schemaplexai.service.rag;

import com.schemaplexai.model.dto.system.RagConfigUpdateRequest;
import com.schemaplexai.model.entity.RagOperationLog;
import com.schemaplexai.model.vo.system.RagConfigVO;
import com.schemaplexai.model.vo.system.RagOperationLogVO;

import java.util.List;

/**
 * RAG 配置服务
 */
public interface RagConfigService {

    RagConfigVO getCurrentTenantConfig();

    RagConfigVO updateCurrentTenantConfig(RagConfigUpdateRequest request);

    List<RagOperationLogVO> listCurrentTenantLogs(String contextId, String sourceType, Integer limit);

    RagRuntimeSettings resolveSettings(String tenantId);

    void recordOperation(RagOperationLog operationLog);
}
