package com.schemaplexai.service.config;

import com.schemaplexai.model.dto.system.MenuCreateRequest;
import com.schemaplexai.model.dto.system.MenuUpdateRequest;
import com.schemaplexai.model.vo.system.MenuVO;

import java.util.List;

/**
 * 菜单管理服务接口
 */
public interface MenuService {

    /**
     * 获取菜单树
     */
    List<MenuVO> getMenuTree();

    /**
     * 获取当前用户的菜单树（按权限过滤）
     */
    List<MenuVO> getCurrentUserMenuTree(String userId);

    MenuVO getMenuById(String id);

    MenuVO createMenu(MenuCreateRequest request);

    MenuVO updateMenu(String id, MenuUpdateRequest request);

    void deleteMenu(String id);
}
