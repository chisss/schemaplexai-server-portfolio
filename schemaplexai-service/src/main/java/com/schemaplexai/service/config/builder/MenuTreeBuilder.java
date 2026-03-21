package com.schemaplexai.service.config.builder;

import com.schemaplexai.model.vo.system.MenuVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 菜单树构建器 — O(n) 算法将扁平 MenuVO 列表构建为树形结构
 */
@Component
public class MenuTreeBuilder {

    /**
     * 将扁平的 MenuVO 列表构建为树形结构
     *
     * @param flatList 扁平菜单列表（需已按 sortOrder 排序）
     * @return 树形菜单列表（顶级节点）
     */
    public List<MenuVO> buildTree(List<MenuVO> flatList) {
        Map<String, MenuVO> map = flatList.stream()
                .collect(Collectors.toMap(MenuVO::getId, v -> v));

        var roots = new ArrayList<MenuVO>();
        for (var item : flatList) {
            if (item.getParentId() == null || !map.containsKey(item.getParentId())) {
                roots.add(item);
            } else {
                var parent = map.get(item.getParentId());
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(item);
            }
        }
        return roots;
    }
}
