package com.schemaplexai.service.context;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.context.*;
import com.schemaplexai.model.vo.context.*;

import com.schemaplexai.model.vo.context.ContextRelationVO;
import java.util.List;
import java.util.Map;

/**
 * 上下文管理服务接口
 */
public interface ContextService {

    // ===== 上下文 CRUD =====

    ContextVO create(ContextCreateRequest request);

    PageResult<ContextVO> page(ContextQueryRequest query);

    ContextDetailVO getById(String id);

    ContextVO update(String id, ContextUpdateRequest request);

    void delete(String id);

    // ===== 关联关系管理 =====

    /** 查询指定上下文的所有关联关系 */
    List<ContextRelationVO> listRelations(String contextId);

    // ===== 条目管理 =====

    ContextItemVO addItem(String contextId, ContextItemCreateRequest request);

    List<ContextItemVO> listItems(String contextId, String itemType);

    ContextItemVO updateItem(String contextId, String itemId, ContextItemUpdateRequest request);

    void deleteItem(String contextId, String itemId);

    // ===== 快照管理 =====

    ContextSnapshotVO createSnapshot(String contextId, String snapshotName);

    List<ContextSnapshotVO> listSnapshots(String contextId);

    ContextSnapshotVO getSnapshotById(String contextId, String snapshotId);

    Map<String, Object> restoreSnapshot(String contextId, String snapshotId);

    // ===== 上下文解析（核心） =====

    ContextResolvedVO resolve(ContextResolveRequest request);
}
