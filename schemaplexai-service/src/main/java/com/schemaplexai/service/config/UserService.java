package com.schemaplexai.service.config;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.system.UserCreateRequest;
import com.schemaplexai.model.dto.system.UserQueryRequest;
import com.schemaplexai.model.dto.system.UserUpdateRequest;
import com.schemaplexai.model.vo.system.UserVO;

/**
 * 用户管理服务接口
 */
public interface UserService {

    /**
     * 分页查询用户列表
     */
    PageResult<UserVO> listUsers(UserQueryRequest request);

    /**
     * 获取用户详情
     */
    UserVO getUserById(String id);

    /**
     * 创建用户
     */
    UserVO createUser(UserCreateRequest request);

    /**
     * 更新用户
     */
    UserVO updateUser(String id, UserUpdateRequest request);

    /**
     * 删除用户
     */
    void deleteUser(String id);

    /**
     * 重置用户密码
     */
    void resetPassword(String id, String newPassword);
}
