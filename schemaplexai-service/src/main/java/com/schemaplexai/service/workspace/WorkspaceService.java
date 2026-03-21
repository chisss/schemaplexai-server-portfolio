package com.schemaplexai.service.workspace;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.workspace.WorkspaceCreateRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceQueryRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceUpdateRequest;
import com.schemaplexai.model.vo.workspace.WorkspaceVO;

/**
 * 工作空间服务接口
 */
public interface WorkspaceService {

    WorkspaceVO create(WorkspaceCreateRequest request);

    PageResult<WorkspaceVO> page(WorkspaceQueryRequest request);

    WorkspaceVO getById(String id);

    WorkspaceVO update(String id, WorkspaceUpdateRequest request);

    void delete(String id);

    WorkspaceVO sync(String id);
}
