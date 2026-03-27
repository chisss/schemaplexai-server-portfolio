package com.schemaplexai.service.auth;

import com.schemaplexai.model.dto.auth.LoginRequest;
import com.schemaplexai.model.dto.auth.RefreshTokenRequest;
import com.schemaplexai.model.vo.auth.LoginVO;
import com.schemaplexai.model.vo.auth.TokenVO;
import com.schemaplexai.model.vo.auth.WsTicketVO;

/**
 * 认证授权服务接口
 */
public interface AuthService {

    /**
     * 用户登录
     *
     * @param request 登录请求
     * @return 登录结果（含Token和用户信息）
     */
    LoginVO login(LoginRequest request);

    /**
     * 刷新Token
     *
     * @param request 刷新Token请求
     * @return 新的Token对
     */
    TokenVO refreshToken(RefreshTokenRequest request);

    /**
     * 用户登出
     *
     * @param userId 用户ID
     */
    void logout(String userId);

    /**
     * 签发短期一次性 WebSocket ticket
     */
    WsTicketVO issueWsTicket(String userId, String tenantId);
}
