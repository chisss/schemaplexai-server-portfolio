package com.schemaplexai.service.context.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.model.entity.ContextItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文条目处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextItemHandler {

    private final ContextItemMapper contextItemMapper;

    /**
     * 估算文本的 Token 数量
     * 简单估算：中文字符按1token/字，英文按4字符/token
     */
    public int calculateTokenCount(String content) {
        if (!StringUtils.hasText(content)) {
            return 0;
        }
        int chineseCount = 0;
        int otherCount = 0;
        for (char c : content.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chineseCount++;
            } else {
                otherCount++;
            }
        }
        return chineseCount + (otherCount / 4) + 1;
    }

    /**
     * 加载上下文的所有条目
     */
    public List<ContextItem> loadItems(String contextId) {
        return contextItemMapper.selectList(
                new LambdaQueryWrapper<ContextItem>()
                        .eq(ContextItem::getContextId, contextId)
                        .orderByAsc(ContextItem::getSortOrder)
        );
    }

    /**
     * 加载上下文条目（按类型过滤）
     */
    public List<ContextItem> loadItems(String contextId, String itemType) {
        var wrapper = new LambdaQueryWrapper<ContextItem>()
                .eq(ContextItem::getContextId, contextId)
                .orderByAsc(ContextItem::getSortOrder);
        if (StringUtils.hasText(itemType)) {
            wrapper.eq(ContextItem::getItemType, itemType);
        }
        return contextItemMapper.selectList(wrapper);
    }

    /**
     * 序列化所有条目为快照数据
     */
    public Map<String, Object> buildSnapshotData(String contextId) {
        var items = loadItems(contextId);
        var itemMaps = items.stream().map(item -> {
            Map<String, Object> map = new HashMap<>();
            map.put("itemType", item.getItemType());
            map.put("title", item.getTitle());
            map.put("content", item.getContent());
            map.put("sourceUrl", item.getSourceUrl());
            map.put("metadata", item.getMetadata());
            map.put("tokenCount", item.getTokenCount());
            map.put("sortOrder", item.getSortOrder());
            return map;
        }).toList();

        Map<String, Object> snapshotData = new HashMap<>();
        snapshotData.put("items", itemMaps);
        return snapshotData;
    }

    /**
     * 计算上下文的条目数量
     */
    public int countItems(String contextId) {
        Long count = contextItemMapper.selectCount(
                new LambdaQueryWrapper<ContextItem>()
                        .eq(ContextItem::getContextId, contextId)
        );
        return count != null ? count.intValue() : 0;
    }

    /**
     * 计算上下文的总 Token 数
     */
    public int sumTokens(String contextId) {
        var items = loadItems(contextId);
        return items.stream()
                .mapToInt(item -> item.getTokenCount() != null ? item.getTokenCount() : 0)
                .sum();
    }

    /**
     * 删除上下文的所有条目
     */
    public void deleteAllItems(String contextId) {
        contextItemMapper.delete(
                new LambdaQueryWrapper<ContextItem>()
                        .eq(ContextItem::getContextId, contextId)
        );
        log.info("清理上下文全部条目: contextId={}", contextId);
    }
}
