package com.schemaplexai.service.security;

/**
 * 服务端权限校验服务
 */
public interface AuthorizationService {

    /**
     * 判断当前登录用户是否拥有指定权限码
     *
     * @param permissionCode 权限码
     * @return true 表示允许访问
     */
    boolean hasPermission(String permissionCode);
}
