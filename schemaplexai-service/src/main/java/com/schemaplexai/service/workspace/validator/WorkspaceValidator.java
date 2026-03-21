package com.schemaplexai.service.workspace.validator;

import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.WorkspaceMapper;
import com.schemaplexai.model.entity.Workspace;
import com.schemaplexai.service.common.EntityValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工作空间业务校验器
 */
@Component
@RequiredArgsConstructor
public class WorkspaceValidator {

    private final WorkspaceMapper workspaceMapper;
    private final EntityValidator entityValidator;

    /**
     * 校验名称唯一性
     */
    public void validateNameUnique(String name) {
        entityValidator.checkUnique(workspaceMapper, Workspace::getName, name,
                ResultCode.WORKSPACE_NAME_DUPLICATE);
    }
}
