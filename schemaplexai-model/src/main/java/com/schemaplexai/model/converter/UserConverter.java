package com.schemaplexai.model.converter;

import com.schemaplexai.common.constants.CommonConstants;
import com.schemaplexai.model.dto.system.UserCreateRequest;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.vo.system.UserVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 用户实体转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE,
        imports = {CommonConstants.class})
public interface UserConverter {

    /**
     * User -> UserVO（基础字段映射，roles 需要额外设置）
     */
    @Mapping(target = "roles", ignore = true)
    UserVO toVO(User user);

    List<UserVO> toVOList(List<User> users);

    /**
     * UserCreateRequest -> User（密码需要单独加密处理）
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "status", expression = "java(CommonConstants.STATUS_ACTIVE)")
    @Mapping(target = "loginType", expression = "java(CommonConstants.LOGIN_TYPE_PASSWORD)")
    @Mapping(target = "lastLoginAt", ignore = true)
    @Mapping(target = "lastLoginIp", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    User fromCreateRequest(UserCreateRequest request);
}
