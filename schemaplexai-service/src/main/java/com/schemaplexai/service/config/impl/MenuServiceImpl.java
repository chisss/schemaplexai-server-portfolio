package com.schemaplexai.service.config.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.MenuMapper;
import com.schemaplexai.model.converter.MenuConverter;
import com.schemaplexai.model.dto.system.MenuCreateRequest;
import com.schemaplexai.model.dto.system.MenuUpdateRequest;
import com.schemaplexai.model.entity.Menu;
import com.schemaplexai.model.vo.system.MenuVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.common.PermissionLoader;
import com.schemaplexai.service.config.MenuService;
import com.schemaplexai.service.config.builder.MenuTreeBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 菜单管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuServiceImpl implements MenuService {

    private final MenuMapper menuMapper;
    private final MenuConverter menuConverter;
    private final MenuTreeBuilder menuTreeBuilder;
    private final PermissionLoader permissionLoader;
    private final EntityValidator entityValidator;

    @Override
    public List<MenuVO> getMenuTree() {
        var wrapper = new LambdaQueryWrapper<Menu>().orderByAsc(Menu::getSortOrder);
        var menus = menuMapper.selectList(wrapper);
        var voList = menuConverter.toVOList(menus);
        return menuTreeBuilder.buildTree(voList);
    }

    @Override
    public List<MenuVO> getCurrentUserMenuTree(String userId) {
        // 1. 加载用户权限编码集合（委托给 PermissionLoader）
        var permCodes = permissionLoader.loadPermissionCodeSet(userId);
        if (permCodes.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 查询所有菜单
        var wrapper = new LambdaQueryWrapper<Menu>().orderByAsc(Menu::getSortOrder);
        var allMenus = menuMapper.selectList(wrapper);

        // 3. 过滤有权限的菜单（permissionCode 为空的菜单默认显示）
        var filteredVOs = allMenus.stream()
                .filter(menu -> menu.getPermissionCode() == null || permCodes.contains(menu.getPermissionCode()))
                .map(menuConverter::toVO)
                .toList();

        return menuTreeBuilder.buildTree(filteredVOs);
    }

    @Override
    public MenuVO getMenuById(String id) {
        var menu = entityValidator.requireExists(menuMapper, id, ResultCode.NOT_FOUND);
        return menuConverter.toVO(menu);
    }

    @Override
    public MenuVO createMenu(MenuCreateRequest request) {
        var menu = menuConverter.fromCreateRequest(request);
        menuMapper.insert(menu);

        log.info("创建菜单成功: menuId={}, name={}", menu.getId(), menu.getName());
        return menuConverter.toVO(menu);
    }

    @Override
    public MenuVO updateMenu(String id, MenuUpdateRequest request) {
        var menu = entityValidator.requireExists(menuMapper, id, ResultCode.NOT_FOUND);
        menuConverter.updateEntityFromRequest(request, menu);
        menuMapper.updateById(menu);

        log.info("更新菜单成功: menuId={}", id);
        return menuConverter.toVO(menu);
    }

    @Override
    public void deleteMenu(String id) {
        entityValidator.requireExists(menuMapper, id, ResultCode.NOT_FOUND);

        // 检查是否有子菜单
        var childCount = menuMapper.selectCount(
                new LambdaQueryWrapper<Menu>().eq(Menu::getParentId, id)
        );
        if (childCount > 0) {
            throw new BusinessException(ResultCode.FAIL, "该菜单下存在子菜单，不允许删除");
        }

        menuMapper.deleteById(id);
        log.info("删除菜单成功: menuId={}", id);
    }
}
