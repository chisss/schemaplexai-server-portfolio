package com.schemaplexai.model.converter;

import com.schemaplexai.model.dto.system.MenuCreateRequest;
import com.schemaplexai.model.dto.system.MenuUpdateRequest;
import com.schemaplexai.model.entity.Menu;
import com.schemaplexai.model.vo.system.MenuVO;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 菜单实体转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface MenuConverter {

    /**
     * Menu -> MenuVO（children 需要额外设置）
     */
    @Mapping(target = "children", ignore = true)
    MenuVO toVO(Menu menu);

    List<MenuVO> toVOList(List<Menu> menus);

    /**
     * MenuCreateRequest -> Menu
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Menu fromCreateRequest(MenuCreateRequest request);

    /**
     * 将 MenuUpdateRequest 的非null字段更新到已有实体
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntityFromRequest(MenuUpdateRequest request, @MappingTarget Menu menu);
}
