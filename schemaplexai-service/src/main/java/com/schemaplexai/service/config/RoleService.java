package com.schemaplexai.service.config;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.system.RoleCreateRequest;
import com.schemaplexai.model.dto.system.RoleQueryRequest;
import com.schemaplexai.model.dto.system.RoleUpdateRequest;
import com.schemaplexai.model.vo.system.RoleVO;

import java.util.List;

/**
 * 角色管理服务接口
 */
public interface RoleService {

    PageResult<RoleVO> listRoles(RoleQueryRequest request);

    List<RoleVO> listAllRoles();

    RoleVO getRoleById(String id);

    RoleVO createRole(RoleCreateRequest request);

    RoleVO updateRole(String id, RoleUpdateRequest request);

    void deleteRole(String id);

    void updateStatus(String id, String status);
}
