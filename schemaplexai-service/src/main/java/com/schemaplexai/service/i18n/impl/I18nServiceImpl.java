package com.schemaplexai.service.i18n.impl;

import cn.hutool.json.JSON;
import cn.hutool.json.JSONParser;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.schemaplexai.common.util.JsonUtil;
import com.schemaplexai.dao.mapper.I18nLocaleMapper;
import com.schemaplexai.dao.mapper.I18nMessageMapper;
import com.schemaplexai.model.entity.I18nLocale;
import com.schemaplexai.model.entity.I18nMessage;
import com.schemaplexai.service.i18n.I18nService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 国际化服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class I18nServiceImpl implements I18nService {

    private final I18nLocaleMapper i18nLocaleMapper;
    private final I18nMessageMapper i18nMessageMapper;
    private final StringRedisTemplate redisTemplate;

    /** Redis缓存key前缀 */
    private static final String CACHE_KEY_PREFIX = "sf:i18n:messages:";

    /** 缓存过期时间：1小时 */
    private static final long CACHE_TTL_HOURS = 1;

    @Override
    public List<I18nLocale> getLocales() {
        return i18nLocaleMapper.selectList(
                new LambdaQueryWrapper<I18nLocale>()
                        .eq(I18nLocale::getEnabled, true)
                        .orderByAsc(I18nLocale::getSortOrder)
        );
    }

    @Override
    public Map<String, Object> getMessages(String locale) {
        var cacheKey = CACHE_KEY_PREFIX + locale;

        // 1. 尝试从Redis缓存读取
        var  cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("命中i18n缓存: locale={}", locale);
            return JSONUtil.toBean(cached, HashMap.class);
        }

        // 2. 缓存未命中，从数据库查询
        log.info("i18n缓存未命中，查询数据库: locale={}", locale);
        var messages = i18nMessageMapper.selectList(
                new LambdaQueryWrapper<I18nMessage>().eq(I18nMessage::getLocale, locale)
        );

        // 3. 将点分隔key转换为嵌套Map结构
        var nestedMap = buildNestedMap(messages);

        // 4. 存入Redis缓存
        redisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(nestedMap), CACHE_TTL_HOURS, TimeUnit.HOURS);
        return nestedMap;
    }

    /**
     * 将点分隔的key列表转换为嵌套Map结构
     * 例如: "common.confirm" -> {common: {confirm: "确认"}}
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildNestedMap(List<I18nMessage> messages) {
        var root = new HashMap<String, Object>();
        for (var msg : messages) {
            var parts = msg.getMsgKey().split("\\.");
            var current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                var value = current.computeIfAbsent(parts[i], k -> new HashMap<>());
                if (!(value instanceof Map)) {
                    // 如果已存在的值不是 Map 类型，创建一个新的 Map 并替换
                    var newMap = new HashMap<String, Object>();
                    current.put(parts[i], newMap);
                    value = newMap;
                }
                current = (HashMap<String, Object>) value;
            }
            current.put(parts[parts.length - 1], msg.getMsgValue());
        }
        return root;
    }
}
